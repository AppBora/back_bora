package br.com.bora.service;

import br.com.bora.entity.*;
import br.com.bora.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * MÓDULO IA — add-on PAGO, liberado loja a loja SOMENTE pelo ADMINISTRADOR_BORA (loja.moduloIa).
 * Recursos: (1) Recuperador de clientes sumidos; (2) Migração de cardápio por foto;
 * (3) Gerente Virtual (resumo diário no WhatsApp do dono).
 * A chave da IA (BORA_CLAUDE_API_KEY) é da PLATAFORMA; sem ela, migração/polimento ficam indisponíveis.
 */
@Slf4j
@Service
public class IaService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final int DIAS_SUMIDO = 21, DIAS_JANELA_ROI = 7;
    /** Teto de disparos de recuperação por loja/mês (protege o custo de template do WhatsApp). */
    private static final int MAX_MSGS_MES = 200;

    private final LojaRepository lojas;
    private final ClienteRepository clientes;
    private final PedidoRepository pedidos;
    private final ProdutoRepository produtos;
    private final IaRecuperacaoRepository recuperacoes;
    private final IntegracaoCanalRepository integracoes;
    private final WhatsAppSender whats;
    private final String claudeKey;

    /** Transacao curta e explicita: ver o item 9.5 em {@link #importarCardapio}. */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public IaService(LojaRepository lojas, ClienteRepository clientes, PedidoRepository pedidos,
                     ProdutoRepository produtos, IaRecuperacaoRepository recuperacoes,
                     IntegracaoCanalRepository integracoes, WhatsAppSender whats,
                     org.springframework.transaction.PlatformTransactionManager gerenciadorDeTransacao,
                     @Value("${bora.claude.api-key:}") String claudeKey) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(gerenciadorDeTransacao);
        this.lojas = lojas;
        this.clientes = clientes;
        this.pedidos = pedidos;
        this.produtos = produtos;
        this.recuperacoes = recuperacoes;
        this.integracoes = integracoes;
        this.whats = whats;
        this.claudeKey = claudeKey;
    }

    /** Gate do add-on: 403 se a loja não contratou (adm ainda não liberou). */
    public Loja exigirModulo(Long lojaId) {
        Loja l = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (!Boolean.TRUE.equals(l.moduloIa)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Módulo IA não contratado. Fale com o BoraHapp para liberar.");
        }
        return l;
    }

    public boolean contratado(Long lojaId) {
        return lojas.findById(lojaId).map(l -> Boolean.TRUE.equals(l.moduloIa)).orElse(false);
    }

    // ---------- (1) RECUPERADOR DE CLIENTES ----------

    /**
     * Dispara a recuperação: clientes com telefone, sumidos há 21+ dias, sem contato nos últimos 30.
     *
     * <h2>Item 9.3 da varredura — dois defeitos no mesmo laço</h2>
     * <p><b>Não tinha teto quando o WhatsApp não respondia.</b> O {@code break} olhava
     * {@code enviados}, e {@code enviados} só subia no ramo de <b>sucesso</b>. Com a Meta fora do ar,
     * nenhum envio dava certo, o contador ficava em zero, o {@code break} nunca disparava e o laço
     * percorria a <b>base inteira</b> — cada cliente com um tempo limite de HTTP pela frente. A
     * varredura estimou ~7 horas com 2.000 clientes.</p>
     *
     * <p><b>E era {@code @Transactional}</b>, então essas horas eram uma transação aberta: uma
     * conexão do pool (de 20) presa, e o PostgreSQL impedido de limpar versões antigas de linha
     * enquanto ela vivesse. Chamada externa dentro de transação é o mesmo defeito que já foi
     * consertado no cobrador de PIX e que os itens 9.4 e 9.5 repetem.</p>
     *
     * <p>Agora: o teto conta <b>tentativas</b>, não sucessos — falha também gasta a cota, que é o
     * que faz o laço terminar. E não há transação em volta: cada {@code recuperacoes.save} é um
     * insert atômico por si, e é assim que tem de ser — se a 40ª mensagem falhar, as 39 que
     * <b>saíram de verdade</b> precisam estar gravadas, senão o cliente recebe de novo no dia
     * seguinte. A transação única fazia o contrário: ou tudo, ou nada do que já aconteceu no mundo.</p>
     */
    public Map<String, Object> recuperarClientes(Long lojaId) {
        Loja loja = exigirModulo(lojaId);
        IntegracaoCanal zap = integracoes.findByLojaIdAndCanal(lojaId, "WHATSAPP")
                .filter(i -> Boolean.TRUE.equals(i.ativo)).orElse(null);
        OffsetDateTime agora = OffsetDateTime.now(ZONE);
        OffsetDateTime inicioMes = agora.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        long usadasNoMes = recuperacoes.countByLojaIdAndEnviadoEmAfter(lojaId, inicioMes);
        long saldo = Math.max(0, MAX_MSGS_MES - usadasNoMes);
        int enviados = 0, candidatos = 0, tentativas = 0;
        // O MESMO N+1 do gerenteResumo, no outro laco: era uma consulta por cliente so para saber a
        // data do ultimo pedido dele. Agora vem num mapa, de uma vez. As decisoes do laco nao
        // mudaram -- ausencia no mapa e o mesmo que o `orElse(null)` de antes.
        java.util.Map<Long, OffsetDateTime> ultimoPedido = new java.util.HashMap<>();
        for (Object[] linha : pedidos.ultimoPedidoPorCliente(lojaId)) {
            ultimoPedido.put((Long) linha[0], (OffsetDateTime) linha[1]);
        }
        for (Cliente c : clientes.findByLojaIdOrderByNomeAsc(lojaId)) {
            // Item 9.3: o teto conta TENTATIVA, nao sucesso. Com `enviados`, WhatsApp fora do ar
            // deixava o contador em zero e o laco percorria a base inteira, um timeout por cliente.
            if (tentativas >= saldo) break; // teto mensal do plano do Módulo IA atingido
            if (c.telefone == null || c.telefone.replaceAll("\\D", "").length() < 10) continue;
            OffsetDateTime ultimo = ultimoPedido.get(c.id);
            if (ultimo == null || ultimo.isAfter(agora.minusDays(DIAS_SUMIDO)) || ultimo.isBefore(agora.minusDays(120))) continue;
            if (recuperacoes.existsByLojaIdAndClienteIdAndEnviadoEmAfter(lojaId, c.id, agora.minusDays(30))) continue;
            candidatos++;
            String texto = "Oi" + (c.nome == null ? "" : " " + c.nome.split(" ")[0]) + "! 😊 Aqui é da *"
                    + loja.nome + "*. Sentimos sua falta por aqui! Que tal pedir de novo hoje? "
                    + "Peça pelo nosso cardápio: https://borahapp.com.br/cardapio.html?loja=" + lojaId;
            if (zap == null) continue;   // sem integracao nao ha o que tentar: nao gasta cota
            tentativas++;
            if (whats.enviar(zap, c.telefone, texto)) {
                IaRecuperacao r = new IaRecuperacao();
                r.setLojaId(lojaId);
                r.setClienteId(c.id);
                r.setTelefone(c.telefone);
                recuperacoes.save(r);
                enviados++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientesSumidos", candidatos);
        out.put("mensagensEnviadas", enviados);
        out.put("saldoMensal", Math.max(0, saldo - enviados));
        // Quantas tentativas falharam. Sem isto, "0 enviadas" podia ser "nao havia quem contatar" ou
        // "o WhatsApp esta fora do ar" -- duas coisas muito diferentes para quem olha a tela.
        if (tentativas > enviados) out.put("falharam", tentativas - enviados);
        if (zap == null) out.put("aviso", "Conecte a integração WhatsApp para o disparo automático.");
        else if (saldo - enviados <= 0) out.put("aviso", "Limite de " + MAX_MSGS_MES + " mensagens/mês do Módulo IA atingido.");
        return out;
    }

    /** ROI dos últimos 30 dias: cliente contatado que pediu de novo em até 7 dias conta como recuperado. */
    public Map<String, Object> resumoRecuperacao(Long lojaId) {
        exigirModulo(lojaId);
        OffsetDateTime corte = OffsetDateTime.now(ZONE).minusDays(30);
        List<IaRecuperacao> envios = recuperacoes.findByLojaIdAndEnviadoEmAfter(lojaId, corte);
        int recuperados = 0;
        BigDecimal valor = BigDecimal.ZERO;
        for (IaRecuperacao r : envios) {
            List<Pedido> depois = pedidos.findByLojaIdAndClienteIdAndCriadoEmAfter(lojaId, r.getClienteId(), r.getEnviadoEm());
            Optional<Pedido> rec = depois.stream()
                    .filter(p -> p.status != StatusPedido.CANCELADO
                            && p.criadoEm.isBefore(r.getEnviadoEm().plusDays(DIAS_JANELA_ROI)))
                    .findFirst();
            if (rec.isPresent()) {
                recuperados++;
                valor = valor.add(rec.get().valorTotal == null ? BigDecimal.ZERO : rec.get().valorTotal);
            }
        }
        return Map.of("contatados30d", envios.size(), "clientesRecuperados", recuperados, "valorRecuperado", valor);
    }

    // ---------- (2) MIGRAÇÃO DE CARDÁPIO POR FOTO ----------

    /**
     * Foto/print do cardápio → produtos cadastrados (IA de visão da plataforma).
     *
     * <h2>Item 9.5 da varredura</h2>
     * <p>Era {@code @Transactional} em volta de <b>tudo</b>, incluindo a chamada à Anthropic, que tem
     * tempo limite de 64 s. Uma conexão do pool (de 20) ficava presa esse tempo inteiro por importação
     * — e importação de cardápio é justamente o que o lojista faz no primeiro dia, às vezes várias
     * vezes seguidas até a foto sair boa.</p>
     *
     * <p>Agora a conversa com a IA acontece <b>fora</b> de transação, e só a gravação dos produtos
     * entra numa — curta, N inserts. A atomicidade que importa fica preservada: ou o cardápio entra
     * inteiro, ou não entra nada. O que se perde é só o tempo de conexão segurada.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importarCardapio(Long lojaId, String imagemBase64, String mediaType) {
        exigirModulo(lojaId);
        if (claudeKey == null || claudeKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "IA da plataforma não configurada (BORA_CLAUDE_API_KEY)");
        }
        if (imagemBase64 == null || imagemBase64.length() < 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie a imagem do cardápio");
        }
        Map<String, Object> resp = TempoLimite.clienteDeIa().build().post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", claudeKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "model", "claude-haiku-4-5-20251001",
                        "max_tokens", 4000,
                        "messages", List.of(Map.of("role", "user", "content", List.of(
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64",
                                        "media_type", mediaType == null ? "image/jpeg" : mediaType,
                                        "data", imagemBase64)),
                                Map.of("type", "text", "text",
                                        "Extraia os itens deste cardápio. Responda SOMENTE um JSON array, sem markdown: "
                                        + "[{\"nome\":\"...\",\"categoria\":\"...\",\"preco\":0.00}]. "
                                        + "Use ponto decimal no preço. Se não houver categoria visível, deduza uma."))))))
                .retrieve().body(Map.class);
        String texto = String.valueOf(((Map<String, Object>) ((List<Object>) resp.get("content")).get(0)).get("text"));
        texto = texto.replaceAll("(?s)```json|```", "").trim();
        List<Map<String, Object>> itens;
        try {
            itens = new com.fasterxml.jackson.databind.ObjectMapper().readValue(texto, List.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Não consegui ler o cardápio da imagem — tente uma foto mais nítida");
        }
        // Item 9.5: a transacao comeca AQUI, depois da IA ter respondido. Antes ela abracava a
        // chamada de 64 s e segurava uma conexao do pool o tempo todo.
        final List<Map<String, Object>> lista = itens;
        int criados = tx.execute(st -> {
            int n = 0;
            for (Map<String, Object> it : lista) {
                String nome = String.valueOf(it.get("nome"));
                if (nome == null || nome.isBlank() || nome.length() > 120) continue;
                Produto p = new Produto();
                p.lojaId = lojaId;
                p.nome = nome.trim();
                p.categoria = it.get("categoria") == null ? "Outros" : String.valueOf(it.get("categoria"));
                try { p.preco = new BigDecimal(String.valueOf(it.get("preco"))); } catch (Exception e) { p.preco = BigDecimal.ZERO; }
                produtos.save(p);
                n++;
            }
            return n;
        });
        return Map.of("produtosCriados", criados);
    }

    // ---------- (3) GERENTE VIRTUAL ----------

    /** Resumo do dia anterior; envia ao WhatsApp do dono (se configurado) e devolve o texto. */
    public Map<String, Object> gerenteResumo(Long lojaId, boolean enviarZap) {
        Loja loja = exigirModulo(lojaId);
        OffsetDateTime hoje0 = OffsetDateTime.now(ZONE).withHour(0).withMinute(0).withSecond(0).withNano(0);
        BigDecimal fatOntem = pedidos.somaReceita(lojaId, hoje0.minusDays(1), hoje0);
        BigDecimal fatAnte = pedidos.somaReceita(lojaId, hoje0.minusDays(2), hoje0.minusDays(1));
        long pedOntem = pedidos.contaPedidosValidos(lojaId, hoje0.minusDays(1), hoje0);
        // UMA CONSULTA, e nao uma por cliente (achado pelo QA em 26/10/2026).
        //
        // Isto carregava TODOS os clientes da loja e, para cada um, buscava o ultimo pedido dele --
        // 1 + N consultas para produzir um unico numero. O `having max(criadoEm)` faz exatamente a
        // mesma conta que o laco fazia: "o primeiro por criadoEm desc" e o maximo.
        //
        // Sobre o tamanho, para nao inflar o motivo: na producao de 27/10/2026 existem 7 clientes no
        // total, entao hoje isso eram 7 consultas e nenhum milissegundo que se note. O conserto esta
        // aqui porque custa uma consulta, e porque a base de clientes e justamente o que cresce numa
        // loja que da certo.
        long sumidos = pedidos.contaClientesComUltimoPedidoEntre(
                lojaId, hoje0.minusDays(120), hoje0.minusDays(DIAS_SUMIDO));
        String var = fatAnte.compareTo(BigDecimal.ZERO) > 0
                ? String.format("%+.0f%%", fatOntem.subtract(fatAnte).multiply(BigDecimal.valueOf(100))
                        .divide(fatAnte, 0, java.math.RoundingMode.HALF_UP).doubleValue())
                : "—";
        String texto = "🤖 *Gerente Virtual — " + loja.nome + "*\n\n"
                + "📊 Ontem: *R$ " + fatOntem.setScale(2, java.math.RoundingMode.HALF_UP).toString().replace('.', ',')
                + "* em " + pedOntem + " pedidos (" + var + " vs anteontem)\n"
                + "💤 Clientes sumidos há 21+ dias: *" + sumidos + "*"
                + (sumidos > 0 ? " — o Recuperador cuida deles no disparo diário." : " 👏")
                + "\n\nBom trabalho hoje! 💪";
        boolean enviado = false;
        if (enviarZap && loja.whatsappDono != null && !loja.whatsappDono.isBlank()) {
            IntegracaoCanal zap = integracoes.findByLojaIdAndCanal(lojaId, "WHATSAPP")
                    .filter(i -> Boolean.TRUE.equals(i.ativo)).orElse(null);
            enviado = whats.enviar(zap, loja.whatsappDono, texto);
        }
        return Map.of("resumo", texto, "enviadoWhatsApp", enviado);
    }

    /** Rotina diária (8h BRT) para todas as lojas com o módulo contratado. */
    public void rotinaDiaria() {
        for (Loja l : lojas.findAll()) {
            if (!Boolean.TRUE.equals(l.moduloIa) || Boolean.FALSE.equals(l.ativo)) continue;
            try {
                gerenteResumo(l.id, true);
                recuperarClientes(l.id);
            } catch (Exception e) {
                log.warn("Módulo IA loja {}: rotina diária falhou: {}", l.id, e.getMessage());
            }
        }
    }
}
