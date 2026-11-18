package br.com.bora.service;

import br.com.bora.entity.Pedido;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Grava o evento na MESMA transação do fato que o gerou. Entrega A2 da fase A.
 *
 * <h2>O que o outbox resolve, e por que a mesma transação importa</h2>
 * <p>Os dois erros clássicos de integração por evento são opostos e igualmente caros:</p>
 * <ul>
 *   <li><b>Venda gravada sem evento</b> — o módulo nunca fica sabendo. Acontece quando o evento é
 *       publicado depois do commit, por um caminho que pode falhar.</li>
 *   <li><b>Evento publicado de uma venda que deu rollback</b> — o módulo processa o que não
 *       aconteceu: baixa estoque de um pedido que não existe, emite nota de uma venda desfeita.</li>
 * </ul>
 * <p>Gravar o evento na mesma transação do fato elimina os dois: ou os dois existem, ou nenhum.</p>
 *
 * <h2>NESTA VERSÃO NINGUÉM CONSOME</h2>
 * <p>A fila enche e fica. Não há publicador, não há consumidor, nenhum módulo do ERP existe ainda.
 * É deliberado: a fase A termina com o módulo principal se comportando exatamente como antes, com
 * a fila cheia e o contrato publicado. A fila retida também é a matéria-prima do gravador de
 * produção que vai alimentar o simulador (A6).</p>
 *
 * <h2>A CHAVE GLOBAL, e por que ela existe</h2>
 * <p>Isto fica no caminho de TODA VENDA. É o único ponto da fase A que pode doer no cliente de
 * hoje. Por isso nasce <b>desligado</b> e tem uma chave que o desliga em produção <b>sem
 * deploy</b>: {@code config_plataforma['outbox.habilitado']}. É o mesmo mecanismo que já governa o
 * módulo fiscal — e se a gravação der qualquer sinal de problema, a chave resolve em segundos.</p>
 *
 * <h2>O QUE PODE FALHAR, E O QUE ACONTECE</h2>
 * <ul>
 *   <li><b>Montar o payload / serializar para JSON</b> — acontece ANTES do INSERT e é capturado:
 *       o evento é descartado com log de erro e <b>a venda segue</b>. Evento perdido se recupera
 *       (o pedido está no banco, dá para reconstruir); venda perdida, não.</li>
 *   <li><b>O INSERT em si</b> — aí não dá para proteger, e é honesto dizer: no PostgreSQL, uma
 *       instrução que falha envenena a transação inteira, então capturar não salvaria a venda de
 *       qualquer jeito. O que resta é fazer o INSERT ser o mais simples possível — colunas simples,
 *       <b>nenhuma chave estrangeira</b> (decisão da V74, justamente para a fila não poder recusar
 *       a gravação da venda) e um JSON já validado.</li>
 * </ul>
 */
@Service
public class PublicadorDeEventos {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublicadorDeEventos.class);

    /** A versão do contrato que esta aplicação escreve. Sobe junto com o schema do evento. */
    public static final String VERSAO_SCHEMA = "1.0.0";

    static final String CHAVE_LIGA = "outbox.habilitado";

    /**
     * Por quanto tempo a resposta da chave vale sem reconsultar.
     *
     * <p>Sem isto, cada venda pagaria um SELECT a mais só para perguntar se deve gravar — e o
     * objetivo deste passo é justamente não pesar no caminho da venda. Trinta segundos é curto o
     * bastante para desligar a chave em produção e ver efeito quase imediato.</p>
     */
    static final long VALIDADE_DA_CHAVE_MS = 30_000;

    private final JdbcTemplate jdbc;
    private final br.com.bora.repository.ConfigPlataformaRepository configs;
    private final ObjectMapper json;

    private volatile boolean ligado = false;
    private volatile long lidaEm = 0L;

    /** Acima disto, UMA gravacao sozinha vira WARN: ela entrou no tempo de uma venda de verdade. */
    public static final long LENTA_MS = 250;

    /** De quantas em quantas gravacoes sai a linha de resumo no log. */
    static final long RESUMO_A_CADA = 20;

    // ----------------------------------------------------------------- a medicao
    //
    // POR QUE A PROPRIA APLICACAO MEDE, e por que isto basta.
    //
    // O custo que o outbox acrescenta a uma venda e EXATAMENTE a duracao deste INSERT: com a chave
    // desligada nao existe INSERT nenhum, entao o "antes" e zero por construcao. Nao e preciso
    // cronometrar a requisicao inteira para saber o delta -- basta cronometrar o que foi
    // acrescentado. Cronometrar a requisicao inteira seria ate PIOR: o tempo dela varia com rede,
    // tamanho do carrinho e concorrencia, e o ruido engoliria um INSERT de poucos milissegundos.
    //
    // E tem de ser medido EM PRODUCAO. No ambiente de desenvolvimento o banco fica na mesma
    // maquina e a diferenca cai no ruido; em producao o banco esta do outro lado da rede, e um
    // INSERT a mais e uma ida e volta a mais.
    //
    // ATENCAO AO LER OS NUMEROS: eles sao DESTE processo, desde que ele subiu. Um deploy ou um
    // restart zera a conta. "0 gravacoes" NAO quer dizer que a chave esta desligada -- quem diz
    // isso e o campo `habilitado`.
    private final java.util.concurrent.atomic.AtomicLong gravacoes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong somaNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong maiorNanos = new java.util.concurrent.atomic.AtomicLong();
    private volatile OffsetDateTime primeiraGravacao;
    private volatile OffsetDateTime ultimaGravacao;

    public PublicadorDeEventos(JdbcTemplate jdbc,
                               br.com.bora.repository.ConfigPlataformaRepository configs,
                               ObjectMapper json) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.json = json;
    }

    /** A chave global, com cache curto. Ausente = desligado. */
    public boolean ligado() {
        long agora = System.currentTimeMillis();
        if (agora - lidaEm > VALIDADE_DA_CHAVE_MS) {
            try {
                ligado = configs.findById(CHAVE_LIGA)
                        .map(c -> Boolean.parseBoolean(c.getValor()))
                        .orElse(false);
            } catch (RuntimeException e) {
                // Não achar a chave não pode derrubar venda. Na dúvida, desligado.
                ligado = false;
            }
            lidaEm = agora;
        }
        return ligado;
    }

    /**
     * Esquece o cache da chave, para a proxima venda ja ver o valor novo.
     *
     * <p>Quem acabou de gravar a chave pela tela esta DENTRO deste mesmo processo, entao pode
     * invalidar o cache e o efeito e imediato. Os 30 segundos continuam valendo como rede de
     * seguranca para quem escrever a chave direto no banco, sem passar pela tela.</p>
     */
    public void forcarReleituraDaChave() {
        lidaEm = 0L;
    }

    /**
     * O estado e o CUSTO da fila, para a tela. Nao grava nada.
     *
     * <p>Os contadores sao deste processo (ver o comentario em cima dos campos): depois de um
     * deploy eles voltam a zero mesmo com a chave ligada. Quem responde "esta ligada?" e o campo
     * {@code habilitado}, nunca a contagem.</p>
     */
    public Map<String, Object> estado() {
        long n = gravacoes.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("habilitado", ligado());
        m.put("gravacoes", n);
        m.put("mediaMs", n == 0 ? null : arredondado(somaNanos.get() / n));
        m.put("maiorMs", n == 0 ? null : arredondado(maiorNanos.get()));
        m.put("primeiraGravacao", primeiraGravacao == null ? null : primeiraGravacao.toString());
        m.put("ultimaGravacao", ultimaGravacao == null ? null : ultimaGravacao.toString());
        m.put("versaoSchema", VERSAO_SCHEMA);
        m.put("lentaAcimaDeMs", LENTA_MS);
        m.put("eventosSemEmpresa", eventosSemEmpresa());
        return m;
    }

    /**
     * Quantos eventos da fila estão sem empresa. <b>No estado saudável isto é sempre zero.</b>
     *
     * <h3>Por que isto está na tela, e não só num comentário</h3>
     * <p>A empresa do evento vem de uma subconsulta em {@code loja} que devolve NULL em silêncio
     * quando não acha -- e silêncio é exatamente o que essa coluna fez até hoje: ela nasceu na V74 e
     * ficou gravada como {@code null} literal, sem ninguém notar, porque nada reclama de uma coluna
     * opcional vazia. Trocar um NULL silencioso por outro não seria conserto.</p>
     *
     * <p>Com este número na tela, a pergunta "a coluna está sendo preenchida?" tem resposta medida em
     * vez de afirmação minha. Se ele sair de zero, o que há é uma loja sem empresa no cadastro -- e
     * aí o lugar de consertar é o cadastro, não a fila.</p>
     *
     * <p>A contagem é barata porque o índice {@code ix_outbox_sem_empresa} (V76) é PARCIAL em
     * {@code empresa_id is null}: no estado saudável ele está vazio, então contar é instantâneo e
     * continua instantâneo com a fila crescendo. É o mesmo desenho do {@code ix_outbox_pendente}.</p>
     *
     * <p><b>Nunca derruba a tela:</b> na dúvida devolve {@code null}, que a tela mostra como "não
     * medido". Esta classe já segue a regra de que medir não pode ser o que quebra.</p>
     */
    private Long eventosSemEmpresa() {
        try {
            return jdbc.queryForObject(
                    "select count(*) from evento_outbox where empresa_id is null", Long.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Grava um evento de pedido na transação corrente.
     *
     * <p><b>Chame de dentro da transação que gravou o fato</b> — se não houver transação aberta, o
     * INSERT vira a sua própria e a garantia do outbox se perde.</p>
     *
     * @param tipo    nome do evento no catálogo (ex.: {@code PedidoFinalizado})
     * @param p       o pedido já gravado, de onde saem loja, agregado e data de operação
     * @param extras  o que mais vai no payload além do básico; pode ser nulo
     */
    public void dePedido(String tipo, Pedido p, Map<String, Object> extras) {
        if (p == null || !ligado()) return;
        String corpo;
        try {
            corpo = json.writeValueAsString(payloadDePedido(p, extras));
        } catch (Exception e) {
            // ANTES do INSERT, e por isso dá para engolir: a venda segue sem o evento.
            log.error("Outbox: nao consegui montar o payload de {} do pedido {} (loja {}). "
                    + "A VENDA SEGUE, o evento foi descartado.", tipo, p.id, p.lojaId, e);
            return;
        }
        long t0 = System.nanoTime();
        try {
            // A EMPRESA SAI DA LOJA, POR SUBCONSULTA DENTRO DESTE MESMO INSERT.
            //
            // Por que a coluna importa: `loja_id` diz em qual unidade a venda aconteceu, e
            // `empresa_id` diz de quem é a unidade. O isolamento do ERP é por empresa (decisão D-02
            // da modelagem), e sem essa coluna o consumidor teria de fazer um JOIN com `loja` em
            // todo evento só para saber de quem é a venda -- ou, pior, tratar cada loja como um
            // cliente separado. MEDIDO em 18/11/2026 no dump de produção das 18:26 UTC: 8 lojas, 4
            // empresas, 0 lojas sem empresa, 0 apontando para empresa inexistente -- e as lojas
            // 18, 19, 20, 21 e 22 (a rede da Zirá) são UMA empresa só, a 3. É exatamente esse caso
            // que a coluna existe para representar.
            //
            // POR QUE SUBCONSULTA, E NÃO UM SELECT EM JAVA. O custo que o outbox acrescenta a uma
            // venda é uma ida e volta ao banco -- é isso que `estado()` mede e que a tela mostra.
            // Ler a empresa em Java antes do INSERT seria uma ida e volta a MAIS: o dobro do custo
            // desta classe, no caminho de toda venda, para buscar um dado que o banco já tem ao
            // lado. Assim é uma busca por chave primária dentro da instrução que já ia acontecer.
            //
            // POR QUE ISSO NÃO PODE DERRUBAR A VENDA. A V74 deixou esta coluna sem NOT NULL e sem
            // chave estrangeira de propósito: fila não pode recusar venda por integridade
            // referencial. A subconsulta mantém essa garantia -- loja inexistente ou loja sem
            // empresa devolvem NULL, não erro, e a venda segue. Fazer a coluna NOT NULL agora seria
            // trocar uma lacuna de cadastro por uma venda perdida, que é o pior dos dois.
            //
            // CUIDADO AO MEXER AQUI: `p.lojaId` vai DUAS vezes, uma para a subconsulta e uma para a
            // coluna `loja_id`. Trocar essas duas entre si é inócuo (são o mesmo valor); o que
            // machuca é mais fácil de fazer:
            //
            //  - pôr `p.id` numa das duas posições de loja -- o evento sai na loja errada, ou com a
            //    empresa de outra loja, EM SILÊNCIO (nada reclama: são dois BIGINT);
            //  - esquecer que a lista cresceu de 7 para 8 parâmetros -- quem lê por posição (os
            //    testes leem) passa a medir outra coisa.
            //
            // Os dois estão medidos: em 18/11/2026 rodei quatro mutantes contra os testes desta
            // classe -- voltar o `null` literal (morto por 4 testes), `p.id` na coluna `loja_id`
            // (1), `p.id` na subconsulta (1) e tirar a contagem da tela (2). Nenhum sobreviveu.
            jdbc.update("""
                    insert into evento_outbox
                      (tipo, versao_schema, empresa_id, loja_id, agregado_tipo, agregado_id,
                       payload, ocorrido_em, data_operacao)
                    values (?, ?, (select empresa_id from loja where id = ?), ?, 'pedido', ?,
                            ?::jsonb, ?, ?)
                    """,
                    tipo, VERSAO_SCHEMA, p.lojaId, p.lojaId, p.id, corpo,
                    java.sql.Timestamp.from(java.time.Instant.now()),
                    java.sql.Date.valueOf(dataDeOperacao(p)));
        } catch (RuntimeException e) {
            // Não há como salvar a venda aqui (ver o javadoc da classe), mas o log tem de deixar
            // claro o que aconteceu para quem for investigar a venda que falhou.
            log.error("Outbox: o INSERT do evento {} do pedido {} falhou e vai derrubar a transacao "
                    + "da venda. DESLIGUE A CHAVE '{}' em config_plataforma.", tipo, p.id, CHAVE_LIGA, e);
            throw e;
        }
        // Chega aqui so quando gravou: o tempo de uma falha nao entra na media de gravacao.
        anotar(System.nanoTime() - t0, tipo, p);
    }

    /**
     * Guarda quanto custou, e grita quando custa demais.
     *
     * <p>Nada aqui pode derrubar a venda: <b>medir nao pode ser o que quebra</b>. Por isso o corpo
     * inteiro esta dentro de um catch que engole -- se a conta ou o log falharem, o pedido ja esta
     * gravado, e e isso que importa.</p>
     */
    private void anotar(long nanos, String tipo, Pedido p) {
        try {
            long n = gravacoes.incrementAndGet();
            somaNanos.addAndGet(nanos);
            maiorNanos.accumulateAndGet(nanos, Math::max);
            OffsetDateTime agora = OffsetDateTime.now();
            ultimaGravacao = agora;

            // A PRIMEIRA sai sempre no log: e o instante, com hora, em que a chave passou a valer de
            // verdade. Sem esta linha, "ligamos a chave" seria afirmacao sem prova.
            if (n == 1) {
                primeiraGravacao = agora;
                log.info("Outbox: PRIMEIRA gravacao deste processo -- evento {} do pedido {} (loja {}) "
                        + "em {} ms. A chave '{}' esta LIGADA e a fila esta recebendo.",
                        tipo, p.id, p.lojaId, emMs(nanos), CHAVE_LIGA);
            }
            if (nanos >= LENTA_MS * 1_000_000L) {
                log.warn("Outbox: a gravacao do evento {} do pedido {} levou {} ms -- isto entrou no "
                        + "tempo de uma venda de verdade. Se repetir, desligue a chave '{}'.",
                        tipo, p.id, emMs(nanos), CHAVE_LIGA);
            }
            if (n % RESUMO_A_CADA == 0) {
                log.info("Outbox: {} gravacoes, media {} ms, maior {} ms. E ESTE o custo que a fila "
                        + "acrescenta a venda -- com a chave desligada ele e zero.",
                        n, emMs(somaNanos.get() / n), emMs(maiorNanos.get()));
            }
        } catch (RuntimeException e) {
            // De proposito: o pedido esta gravado. Perder a medicao e barato; perder a venda nao.
        }
    }

    private static String emMs(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static double arredondado(long nanos) {
        return Math.round(nanos / 1_000_000.0 * 10.0) / 10.0;
    }

    /**
     * O básico que todo evento de pedido carrega. Não é o payload canônico completo do contrato —
     * esse só é congelado na entrega A5, e inventá-lo agora seria publicar um contrato que ninguém
     * leu. O que vai aqui é o mínimo que permite ao consumidor achar o pedido e saber o que mudou.
     */
    private Map<String, Object> payloadDePedido(Pedido p, Map<String, Object> extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pedidoId", p.id);
        m.put("lojaId", p.lojaId);
        m.put("codigo", p.codigo);
        m.put("status", p.status == null ? null : p.status.name());
        m.put("origem", p.origem);
        m.put("valorTotal", p.valorTotal);
        m.put("formaPagamento", p.formaPagamento);
        m.put("tipoEntrega", p.tipoEntrega);
        m.put("criadoEm", p.criadoEm == null ? null : p.criadoEm.toString());
        if (extras != null) extras.forEach((k, v) -> m.put(k, saneado(v)));
        // SANEIA TODO VALOR DE TEXTO, inclusive o que ja estava no mapa: `codigo`, `origem` e
        // `formaPagamento` vem do banco e podem ter vindo de marketplace.
        m.replaceAll((k, v) -> saneado(v));
        return m;
    }

    /**
     * Tira do valor os DOIS caracteres que o {@code jsonb} do PostgreSQL recusa.
     *
     * <h3>O defeito que isto fecha, medido em 18/11/2026</h3>
     * <p>Medido num PostgreSQL 16 de verdade:</p>
     * <pre>
     *   select '{"a":"x\u0000y"}'::jsonb;  -- ERROR: unsupported Unicode escape sequence
     *   select '{"a":"\ud800"}'::jsonb;     -- ERROR: invalid input syntax for type json
     *   select '{"a":"Acai 500ml"}'::jsonb;  -- passa (acento nao e problema)
     * </pre>
     *
     * <p>Por que isso era grave: o INSERT desta classe mora na transacao da VENDA, e o javadoc da
     * classe diz, com razao, que uma instrucao que falha envenena a transacao inteira — nao da para
     * capturar e seguir. Ou seja: <b>um caractere NUL dentro de um "motivo" de cancelamento ou de um
     * id externo de marketplace derrubava a venda</b>. E o {@code catch} que protege a serializacao
     * nao ajudava, porque o Jackson serializa o NUL sem reclamar; quem recusa e o banco, depois.</p>
     *
     * <p><b>Por que sanear e nao recusar:</b> NUL dentro de um texto de operacao e lixo de
     * codificacao, nunca informacao. Perder o lixo e barato; perder a venda, nao.</p>
     *
     * <p>Os dois casos:</p>
     * <ul>
     *   <li><b>U+0000</b> — o NUL. Chega por copiar e colar, por campo de sistema legado, ou por
     *       texto que passou por conversao de codificacao torta.</li>
     *   <li><b>Surrogate solto</b> — meio de um emoji. Aparece quando um texto e CORTADO por
     *       tamanho no meio de um par (e este sistema corta por largura de coluna em varios
     *       pontos, justamente para o Postgres nao recusar o INSERT). Um par completo passa: emoji
     *       inteiro nao e problema.</li>
     * </ul>
     */
    static Object saneado(Object valor) {
        if (!(valor instanceof String texto) || texto.isEmpty()) return valor;
        StringBuilder limpo = null;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            boolean recusado = c == '\u0000'
                    // alto sem baixo depois, ou baixo sem alto antes: meio de par
                    || (Character.isHighSurrogate(c)
                        && (i + 1 >= texto.length() || !Character.isLowSurrogate(texto.charAt(i + 1))))
                    || (Character.isLowSurrogate(c)
                        && (i == 0 || !Character.isHighSurrogate(texto.charAt(i - 1))));
            if (recusado) {
                if (limpo == null) limpo = new StringBuilder(texto.length()).append(texto, 0, i);
            } else if (limpo != null) {
                limpo.append(c);
            }
        }
        return limpo == null ? texto : limpo.toString();
    }

    /**
     * O DIA OPERACIONAL do pedido, que não é o mesmo que a data do instante.
     *
     * <p>Usa a hora do próprio pedido quando ela existe, e não {@code now()}: um evento gravado às
     * 00h05 por causa de uma reentrega ou de um status tardio pertence ao dia do pedido, não ao dia
     * seguinte. Foi um fuso em UTC que já produziu duas datas de cobrança erradas neste sistema.</p>
     */
    private java.time.LocalDate dataDeOperacao(Pedido p) {
        OffsetDateTime quando = p.criadoEm != null ? p.criadoEm : OffsetDateTime.now();
        return quando.toLocalDate();
    }
}
