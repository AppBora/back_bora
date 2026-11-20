package br.com.bora.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A RETENÇÃO da fila de eventos do ERP. (item 8 da seção 4 da varredura, 20/11/2026)
 *
 * <h2>O defeito</h2>
 * <p>{@code evento_outbox} nasceu na V74 e <b>cresce para sempre</b>: são até 6 eventos por pedido,
 * ~620 bytes cada, e ninguém apaga nada — não há publicador nem consumidor ainda, então toda linha
 * gravada fica. Com 23 lojas vendendo, é crescimento contínuo num banco que ninguém está olhando, e
 * a primeira pessoa a notar seria o disco.</p>
 *
 * <h2>A decisão que eu tomei, e ela está na cara</h2>
 * <p>A varredura dizia que "a decisão de retenção deve vir <b>junto</b> com o consumidor, não
 * depois", e isso continua certo: apagar evento que ninguém leu é perder dado de produção para
 * sempre. Então o que entra é o <b>mecanismo</b>, com duas chaves separadas e as duas desligadas:</p>
 * <ul>
 *   <li>{@value #CHAVE_DIAS} — apaga evento <b>já publicado</b> com mais de N dias. É limpeza de
 *       histórico já entregue, e é a decisão fácil.</li>
 *   <li>{@value #CHAVE_DIAS_PENDENTES} — apaga evento <b>não publicado</b> com mais de N dias. Esta
 *       é a que joga fora o que ninguém leu, e é por isso que ela é uma chave <b>separada</b>, e não
 *       um número só: as duas não são a mesma decisão.</li>
 * </ul>
 *
 * <p><b>Ausente = não apaga nada</b>, nos dois casos. Com a fila como está hoje (nada publica,
 * nenhuma chave definida), esta classe roda uma vez por dia e apaga <b>zero</b> — e diz isso no log.
 * Quem muda isso é o dono, pela tela da plataforma, sem deploy.</p>
 *
 * <h2>O que ela faz MESMO sem retenção ligada: tornar o crescimento visível</h2>
 * <p>O pior do item 8 não era a falta do DELETE — era que o tamanho da fila não aparecia em lugar
 * nenhum. Então {@link #estado()} leva para a tela quantos eventos existem, quantos estão pendentes
 * e qual a idade do mais antigo, e o log sai em WARN quando a fila passa de
 * {@value #TETO_DE_AVISO} linhas. É a mesma correção de fundo do vigia: "está tudo bem" tem de ser
 * uma frase medida, não silêncio.</p>
 *
 * <h2>Em LOTE, e por quê</h2>
 * <p>Um {@code DELETE} que pega 2 milhões de linhas de uma vez mantém lock e infla o WAL por
 * minutos, no mesmo banco onde as lojas estão vendendo. Cada execução apaga no máximo
 * {@value #LOTE} linhas por chave; o que sobrar sai na execução de amanhã. Retenção é faxina, não
 * emergência — e uma faxina que derruba a venda é pior do que o lixo.</p>
 */
@Service
public class RetencaoDaFila {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RetencaoDaFila.class);

    /** Apaga evento JÁ PUBLICADO com mais de N dias. Ausente/0 = não apaga. */
    public static final String CHAVE_DIAS = "outbox.retencao.dias";
    /** Apaga evento NÃO publicado com mais de N dias. Ausente/0 = não apaga. É a decisão do dono. */
    public static final String CHAVE_DIAS_PENDENTES = "outbox.retencao.dias-pendentes";

    /** Teto por execução e por chave. Ver "Em LOTE" no javadoc da classe. */
    // Publico porque o IT da retencao gera LOTE+5 linhas para provar que o teto vale de verdade:
    // copiar o numero no teste faria o teste medir o numero dele, nao este.
    public static final int LOTE = 5_000;

    /** Acima disto a fila vira WARN no log a cada execução. ~620 bytes/evento => ~124 MB. */
    static final long TETO_DE_AVISO = 200_000;

    private final JdbcTemplate jdbc;
    private final br.com.bora.repository.ConfigPlataformaRepository configs;

    private volatile String ultimaLimpeza;
    private volatile long apagadosNaUltima = -1;

    public RetencaoDaFila(JdbcTemplate jdbc, br.com.bora.repository.ConfigPlataformaRepository configs) {
        this.jdbc = jdbc;
        this.configs = configs;
    }

    /**
     * Uma vez por dia, de madrugada no fuso de Brasília.
     *
     * <p>04h20 e não 04h00 de propósito: o backup e as outras rotinas de madrugada caem em hora
     * redonda, e empilhar faxina com backup no mesmo minuto é como se descobre que os dois
     * concorrem. O fuso é explícito porque o servidor fala UTC — foi um fuso em UTC que já travou o
     * deploy deste projeto das 21h à meia-noite e produziu duas datas de cobrança erradas.</p>
     */
    @Scheduled(cron = "0 20 4 * * *", zone = "America/Sao_Paulo")
    public void rotinaDiaria() {
        try {
            limpar();
        } catch (RuntimeException e) {
            // Faxina que falha não pode derrubar nada: ela tenta de novo amanhã. Em ERROR porque a
            // fila parar de ser limpa é coisa que ninguém nota até o disco encher.
            log.error("Retencao da fila: a limpeza de hoje FALHOU. A fila continua crescendo; "
                    + "confira as chaves '{}' e '{}'.", CHAVE_DIAS, CHAVE_DIAS_PENDENTES, e);
        }
    }

    /**
     * Apaga o que as chaves autorizarem e devolve o que foi feito. Idempotente e segura de repetir.
     *
     * @return mapa com o que cada chave apagou, para a tela e para o log
     */
    public Map<String, Object> limpar() {
        Integer dias = diasDaChave(CHAVE_DIAS);
        Integer diasPendentes = diasDaChave(CHAVE_DIAS_PENDENTES);

        long publicados = dias == null ? 0 : apagar(
                "delete from evento_outbox where ctid in ("
                + "  select ctid from evento_outbox"
                + "   where publicado_em is not null"
                + "     and criado_em < now() - make_interval(days => ?)"
                + "   limit " + LOTE + ")", dias);

        long pendentes = diasPendentes == null ? 0 : apagar(
                "delete from evento_outbox where ctid in ("
                + "  select ctid from evento_outbox"
                + "   where publicado_em is null"
                + "     and criado_em < now() - make_interval(days => ?)"
                + "   limit " + LOTE + ")", diasPendentes);

        // A idempotência do consumidor sem o evento correspondente não serve a nada: o que ela
        // protege é o reprocessamento de um evento que ainda existe. Sem esta limpeza, apagar da
        // fila deixaria `evento_consumido` crescendo sozinho, que é o mesmo defeito uma tabela ao
        // lado. Só roda quando algo foi apagado — sem fila apagada não há linha órfã nova.
        long consumidos = (publicados + pendentes) == 0 ? 0 : apagarSemParametro(
                "delete from evento_consumido where ctid in ("
                + "  select c.ctid from evento_consumido c"
                + "   where not exists (select 1 from evento_outbox o where o.evento_id = c.evento_id)"
                + "   limit " + LOTE + ")");

        long total = publicados + pendentes + consumidos;
        ultimaLimpeza = java.time.OffsetDateTime.now().toString();
        apagadosNaUltima = total;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("publicadosApagados", publicados);
        r.put("pendentesApagados", pendentes);
        r.put("consumidosApagados", consumidos);
        r.put("retencaoDias", dias);
        r.put("retencaoDiasPendentes", diasPendentes);

        Long naFila = conta("select count(*) from evento_outbox");
        r.put("naFila", naFila);

        if (dias == null && diasPendentes == null) {
            // Dizer isto todo dia é de propósito: "a fila não é limpa" tem de estar escrito em algum
            // lugar que alguém lê, e não ser deduzido da ausência de linhas de log.
            log.info("Retencao da fila: NENHUMA chave de retencao definida -- nada foi apagado, e a "
                    + "fila tem {} evento(s). Para ligar, defina '{}' (ja publicados) e/ou '{}' "
                    + "(nao publicados) em config_plataforma.", naFila, CHAVE_DIAS, CHAVE_DIAS_PENDENTES);
        } else {
            log.info("Retencao da fila: apaguei {} publicado(s), {} pendente(s) e {} linha(s) de "
                    + "evento_consumido. Sobraram {} na fila. Tetos: {} e {} dias, no maximo {} por "
                    + "execucao.", publicados, pendentes, consumidos, naFila, dias, diasPendentes, LOTE);
        }
        if (naFila != null && naFila > TETO_DE_AVISO) {
            log.warn("Retencao da fila: a fila tem {} eventos, acima do teto de aviso de {}. "
                    + "Ninguem consome a fila ainda; se isto nao for intencional, defina '{}'.",
                    naFila, TETO_DE_AVISO, CHAVE_DIAS_PENDENTES);
        }
        return r;
    }

    /**
     * O tamanho e a idade da fila, para a tela da plataforma. Nunca lança.
     *
     * <p>Devolve {@code null} em cada número que não deu para medir — "não medido" é uma resposta
     * honesta, e medir não pode ser o que quebra a tela (mesma regra do
     * {@code PublicadorDeEventos.estado()}).</p>
     */
    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("naFila", conta("select count(*) from evento_outbox"));
        m.put("pendentes", conta("select count(*) from evento_outbox where publicado_em is null"));
        m.put("maisAntigoDias", conta(
                "select floor(extract(epoch from (now() - min(criado_em))) / 86400)::bigint "
                + "from evento_outbox"));
        m.put("retencaoDias", diasDaChave(CHAVE_DIAS));
        m.put("retencaoDiasPendentes", diasDaChave(CHAVE_DIAS_PENDENTES));
        m.put("ultimaLimpeza", ultimaLimpeza);
        m.put("apagadosNaUltimaLimpeza", apagadosNaUltima < 0 ? null : apagadosNaUltima);
        m.put("tetoDeAvisoLinhas", TETO_DE_AVISO);
        m.put("maximoPorExecucao", LOTE);
        return m;
    }

    /**
     * O número de dias de uma chave, ou {@code null} quando ela não vale como retenção.
     *
     * <p><b>Zero, vazio, texto e negativo são todos "não apaga nada"</b>, e isto é escolha: a
     * alternativa — tratar valor inválido como zero dias — faria um erro de digitação na tela
     * apagar a fila inteira. Entre "a faxina não roda" e "a faxina apaga tudo", a primeira é a que
     * se descobre sem perder dado.</p>
     *
     * <p>A leitura é protegida: {@code config_plataforma.valor} é cifrado em repouso e lançar aqui
     * (chave de cifragem ausente ou trocada) não pode virar exceção na rotina da madrugada.</p>
     */
    Integer diasDaChave(String chave) {
        try {
            String valor = configs.findById(chave).map(c -> c.getValor()).orElse(null);
            if (valor == null || valor.isBlank()) return null;
            int dias = Integer.parseInt(valor.trim());
            return dias > 0 ? dias : null;
        } catch (NumberFormatException e) {
            log.warn("Retencao da fila: a chave '{}' nao e um numero de dias valido -- nada sera "
                    + "apagado por ela.", chave);
            return null;
        } catch (RuntimeException e) {
            log.error("Retencao da fila: nao consegui LER a chave '{}'. Nada sera apagado. O motivo "
                    + "mais provavel e a chave de cifragem das colunas (BORA_SEGREDO_COLUNAS).", chave, e);
            return null;
        }
    }

    private long apagar(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /**
     * O DELETE sem parametro.
     *
     * <p>Existe como metodo proprio porque {@code jdbc.update(sql)} e
     * {@code jdbc.update(sql, new Object[0])} sao sobrecargas DIFERENTES, e chamar a de varargs com
     * lista vazia e o tipo de detalhe que faz um teste medir outra coisa.</p>
     */
    private long apagarSemParametro(String sql) {
        return jdbc.update(sql);
    }

    private Long conta(String sql) {
        try {
            return jdbc.queryForObject(sql, Long.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
