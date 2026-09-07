package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agente de estratégia da rede: lê o que as abas de Rede &amp; Análise mostram e propõe o que fazer.
 *
 * <p>A tela já respondia "como foi"; faltava "e agora?". Os números estavam lá — queda de venda às
 * 15h, produto encalhado, canal comendo 27% em comissão, pedido parado 40 minutos em preparo — mas
 * ler tudo isso e transformar em decisão exigia tempo que dono de loja não tem.</p>
 *
 * <p>Duas metades separadas de propósito: {@link #dossie} monta os dados (roda sem IA nenhuma, dá
 * para conferir número por número) e {@link #analisar} manda esse dossiê para o Claude. Se a IA
 * estiver fora do ar ou sem crédito, o dossiê continua servindo.</p>
 *
 * <p>O agente <b>propõe</b>; quem executa é o lojista. Nenhuma recomendação altera preço, cria
 * promoção ou dispara mensagem sozinha: cada uma vira um botão que leva à tela certa, já apontada
 * para o problema. Mexer no dinheiro do cliente sem ele mandar não é assistência, é susto.</p>
 */
@Service
public class AgenteRedeService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final AnaliseRedeService analise;
    private final RedeService rede;
    private final IaService ia;
    private final LojaRepository lojas;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final ProdutoRepository produtos;
    private final UsuarioLojaRepository vinculos;
    private final AuthContext ctx;
    private final String claudeKey;

    public AgenteRedeService(AnaliseRedeService analise, RedeService rede, IaService ia,
                             LojaRepository lojas, PedidoRepository pedidos, PedidoItemRepository itens,
                             ProdutoRepository produtos, UsuarioLojaRepository vinculos, AuthContext ctx,
                             @Value("${bora.claude.api-key:}") String claudeKey) {
        this.analise = analise;
        this.rede = rede;
        this.ia = ia;
        this.lojas = lojas;
        this.pedidos = pedidos;
        this.itens = itens;
        this.produtos = produtos;
        this.vinculos = vinculos;
        this.ctx = ctx;
        this.claudeKey = claudeKey;
    }

    // ------------------------------------------------------------------ dossiê

    /** Tudo que as quatro abas mostram, mais estoque, num pacote só — sem chamar IA. */
    public Map<String, Object> dossie(LocalDate inicio, LocalDate fim) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("balancete", rede.balancete(inicio, fim));
        d.put("canais", analise.canais(inicio, fim));
        d.put("horario", analise.horario(inicio, fim));
        d.put("tempos", analise.tempos(inicio, fim));
        d.put("estoque", estoqueEmRisco(inicio, fim));
        d.put("cancelamentosRecentes", cancelamentosRecentes(inicio, fim));
        return d;
    }

    /** Lojas da rede do usuário (a plataforma enxerga todas as ativas). */
    private List<Loja> minhasLojas() {
        if (ctx.isAdminBora()) {
            return lojas.findAll().stream().filter(l -> !l.arquivada()).toList();
        }
        List<Loja> out = new ArrayList<>();
        for (UsuarioLoja v : vinculos.findByUsuarioId(ctx.atual().userId())) {
            lojas.findById(v.getLojaId()).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Produtos que a demanda do período derruba antes da próxima compra.
     *
     * <p>Estoque parado não diz nada sozinho: 10 unidades é muito para quem vende 1 por semana e é
     * ruptura amanhã para quem vende 20 por dia. O que interessa é a cobertura em dias.</p>
     */
    private List<Map<String, Object>> estoqueEmRisco(LocalDate inicio, LocalDate fim) {
        LocalDate ini = inicio == null ? LocalDate.now(ZONE).withDayOfMonth(1) : inicio;
        LocalDate f = fim == null ? LocalDate.now(ZONE) : fim;
        long dias = Math.max(1, ChronoUnit.DAYS.between(ini, f) + 1);
        OffsetDateTime corte = ini.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimExc = f.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Loja l : minhasLojas()) {
            List<Pedido> vendas = pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), corte).stream()
                    .filter(p -> p.criadoEm != null && p.criadoEm.isBefore(fimExc) && p.status != StatusPedido.CANCELADO)
                    .toList();
            if (vendas.isEmpty()) continue;
            Map<Long, Integer> vendidoPorProduto = new HashMap<>();
            for (PedidoItem it : itens.findByLojaIdAndPedidoIdIn(l.getId(), vendas.stream().map(p -> p.id).toList())) {
                if (it.getProdutoId() == null) continue;
                vendidoPorProduto.merge(it.getProdutoId(), it.getQuantidade() == null ? 1 : it.getQuantidade(), Integer::sum);
            }
            for (Produto p : produtos.findByLojaIdOrderByNomeAsc(l.getId())) {
                if (p.estoque == null || !Boolean.TRUE.equals(p.ativo)) continue; // sem controle de estoque, sem alerta
                int vendido = vendidoPorProduto.getOrDefault(p.id, 0);
                if (vendido == 0) continue;
                BigDecimal porDia = BigDecimal.valueOf(vendido).divide(BigDecimal.valueOf(dias), 2, RoundingMode.HALF_UP);
                if (porDia.signum() == 0) continue;
                BigDecimal cobertura = BigDecimal.valueOf(p.estoque).divide(porDia, 1, RoundingMode.HALF_UP);
                if (cobertura.compareTo(BigDecimal.valueOf(7)) > 0) continue; // mais de uma semana: não é urgência
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("loja", l.getNome());
                m.put("lojaId", l.getId());
                m.put("produto", p.nome);
                m.put("estoque", p.estoque);
                m.put("estoqueMinimo", p.estoqueMinimo);
                m.put("vendidoNoPeriodo", vendido);
                m.put("mediaPorDia", porDia);
                m.put("diasDeCobertura", cobertura);
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> (BigDecimal) m.get("diasDeCobertura")));
        return out.size() > 15 ? out.subList(0, 15) : out;
    }

    /** Cancelados do período com motivo e canal — é onde mora a causa que dá para atacar. */
    private List<Map<String, Object>> cancelamentosRecentes(LocalDate inicio, LocalDate fim) {
        LocalDate ini = inicio == null ? LocalDate.now(ZONE).withDayOfMonth(1) : inicio;
        LocalDate f = fim == null ? LocalDate.now(ZONE) : fim;
        OffsetDateTime corte = ini.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimExc = f.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Loja l : minhasLojas()) {
            for (Pedido p : pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), corte)) {
                if (p.criadoEm == null || !p.criadoEm.isBefore(fimExc) || p.status != StatusPedido.CANCELADO) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pedidoId", p.id);
                m.put("loja", l.getNome());
                m.put("lojaId", l.getId());
                m.put("codigo", p.codigo);
                m.put("valor", p.valorTotal);
                m.put("canal", p.origem);
                m.put("motivo", p.motivoCancelamento);
                m.put("clienteTelefone", p.clienteTelefone);
                m.put("quando", p.canceladoEm == null ? p.criadoEm : p.canceladoEm);
                out.add(m);
                if (out.size() >= 30) return out;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ IA

    private static final String PAPEL = """
        Você é o agente de estratégia do BoraHapp, um SaaS de delivery white-label.
        Sua formação: doutor em gestão de empresas, especialista em marketing e vendas de food service,
        e conhecedor profundo do próprio Bora — você sabe o que cada tela do painel faz.

        Você recebe o dossiê real da rede do lojista (faturamento por loja, canais e comissões,
        produtos que mais e menos vendem, vendas por faixa de horário, tempos de cada etapa do pedido,
        cancelamentos e cobertura de estoque) e devolve DECISÕES, não descrições.

        Regras que você não quebra:
        - Fale com o dono da loja, não com um analista. Frases curtas, português do Brasil, sem jargão.
        - Cada recomendação cita o número do dossiê que a sustenta. Sem número, não é recomendação.
        - Nunca invente dado que não está no dossiê. Se algo não dá para concluir, diga que falta dado.
        - Período curto ou pouca venda: diga que a amostra é pequena em vez de fingir tendência.
        - Priorize pelo dinheiro em jogo. Três recomendações boas valem mais que dez genéricas.
        - Você propõe; quem aplica é o lojista. Nunca escreva como se já tivesse feito algo.

        Telas do Bora que suas ações podem abrir (use exatamente estes valores em "tela"):
        - promocoes.html  → criar promoção, cupom, promoção relâmpago para horário fraco
        - produtos.html   → preço, foto, ativar/desativar, produto encalhado
        - estoque.html    → reposição e compra
        - crm.html        → clientes sumidos, recuperação
        - pedidos.html    → investigar pedidos e cancelamentos
        - canais.html     → custo e desempenho por canal
        - relatorios.html → detalhe por loja
        - desempenho.html → lucro por loja
        - ajustes.html    → horário de funcionamento e operação

        Responda SOMENTE um JSON válido, sem markdown, neste formato:
        {
          "resumo": "2 a 3 frases sobre a situação da rede no período",
          "recomendacoes": [
            {
              "titulo": "frase curta e imperativa",
              "categoria": "CANCELAMENTO|HORARIO|PRODUTO|ESTOQUE|CANAL|LOJA|CLIENTE",
              "impacto": "ALTO|MEDIO|BAIXO",
              "porque": "o número do dossiê que sustenta isso",
              "comoFazer": "o passo a passo dentro do Bora, em 1 ou 2 frases",
              "ganhoEstimado": "estimativa em R$ ou %, ou null se não der para estimar",
              "acao": { "tela": "promocoes.html", "rotulo": "Criar promoção das 15h" }
            }
          ]
        }
        Para falar com um cliente de pedido cancelado, use "tela": "whatsapp" e inclua
        "telefone" e "mensagem" (texto pronto, cordial, perguntando o que houve) dentro de "acao".
        """;

    /** Manda o dossiê para o Claude e devolve o plano já estruturado para a tela. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analisar(LocalDate inicio, LocalDate fim) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        if (!ctx.isAdminBora()) ia.exigirModulo(ctx.lojaId()); // add-on pago, como o resto do módulo
        if (claudeKey == null || claudeKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "IA da plataforma não configurada (BORA_CLAUDE_API_KEY)");
        }

        Map<String, Object> dossie = dossie(inicio, fim);
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(dossie);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Falha ao montar o dossiê");
        }

        Map<String, Object> resp;
        try {
            resp = RestClient.create().post()
                    .uri("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", claudeKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "model", "claude-sonnet-5",
                            "max_tokens", 4000,
                            "system", PAPEL,
                            "messages", List.of(Map.of("role", "user", "content",
                                    "Dossiê da rede (JSON):\n" + json))))
                    .retrieve().body(Map.class);
        } catch (Exception e) {
            // sem crédito, chave revogada, rede fora: o lojista precisa saber o que fazer, não ver stacktrace
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não consegui falar com a IA agora. Verifique a chave e o saldo da conta Anthropic da plataforma.");
        }

        String texto;
        try {
            texto = String.valueOf(((Map<String, Object>) ((List<Object>) resp.get("content")).get(0)).get("text"))
                    .replaceAll("(?s)```json|```", "").trim();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resposta da IA em formato inesperado");
        }

        Map<String, Object> plano;
        try {
            plano = new ObjectMapper().readValue(texto, Map.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A IA respondeu fora do formato esperado. Tente de novo.");
        }
        Map<String, Object> out = new LinkedHashMap<>(plano);
        out.put("inicio", ((Map<String, Object>) dossie.get("balancete")).get("inicio"));
        out.put("fim", ((Map<String, Object>) dossie.get("balancete")).get("fim"));
        return out;
    }
}
