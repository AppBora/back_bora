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

    private final LojaRepository lojas;
    private final ClienteRepository clientes;
    private final PedidoRepository pedidos;
    private final ProdutoRepository produtos;
    private final IaRecuperacaoRepository recuperacoes;
    private final IntegracaoCanalRepository integracoes;
    private final WhatsAppSender whats;
    private final String claudeKey;

    public IaService(LojaRepository lojas, ClienteRepository clientes, PedidoRepository pedidos,
                     ProdutoRepository produtos, IaRecuperacaoRepository recuperacoes,
                     IntegracaoCanalRepository integracoes, WhatsAppSender whats,
                     @Value("${bora.claude.api-key:}") String claudeKey) {
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

    /** Dispara a recuperação: clientes com telefone, sumidos há 21+ dias, sem contato nos últimos 30. */
    @Transactional
    public Map<String, Object> recuperarClientes(Long lojaId) {
        Loja loja = exigirModulo(lojaId);
        IntegracaoCanal zap = integracoes.findByLojaIdAndCanal(lojaId, "WHATSAPP")
                .filter(i -> Boolean.TRUE.equals(i.ativo)).orElse(null);
        OffsetDateTime agora = OffsetDateTime.now(ZONE);
        int enviados = 0, candidatos = 0;
        for (Cliente c : clientes.findByLojaIdOrderByNomeAsc(lojaId)) {
            if (c.telefone == null || c.telefone.replaceAll("\\D", "").length() < 10) continue;
            OffsetDateTime ultimo = pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(lojaId, c.id)
                    .map(p -> p.criadoEm).orElse(null);
            if (ultimo == null || ultimo.isAfter(agora.minusDays(DIAS_SUMIDO)) || ultimo.isBefore(agora.minusDays(120))) continue;
            if (recuperacoes.existsByLojaIdAndClienteIdAndEnviadoEmAfter(lojaId, c.id, agora.minusDays(30))) continue;
            candidatos++;
            String texto = "Oi" + (c.nome == null ? "" : " " + c.nome.split(" ")[0]) + "! 😊 Aqui é da *"
                    + loja.nome + "*. Sentimos sua falta por aqui! Que tal pedir de novo hoje? "
                    + "Peça pelo nosso cardápio: https://borahapp.com.br/cardapio.html?loja=" + lojaId;
            if (zap != null && whats.enviar(zap, c.telefone, texto)) {
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
        if (zap == null) out.put("aviso", "Conecte a integração WhatsApp para o disparo automático.");
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

    /** Foto/print do cardápio → produtos cadastrados (IA de visão da plataforma). */
    @Transactional
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
        Map<String, Object> resp = RestClient.create().post()
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
        int criados = 0;
        for (Map<String, Object> it : itens) {
            String nome = String.valueOf(it.get("nome"));
            if (nome == null || nome.isBlank() || nome.length() > 120) continue;
            Produto p = new Produto();
            p.lojaId = lojaId;
            p.nome = nome.trim();
            p.categoria = it.get("categoria") == null ? "Outros" : String.valueOf(it.get("categoria"));
            try { p.preco = new BigDecimal(String.valueOf(it.get("preco"))); } catch (Exception e) { p.preco = BigDecimal.ZERO; }
            produtos.save(p);
            criados++;
        }
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
        long sumidos = clientes.findByLojaIdOrderByNomeAsc(lojaId).stream().filter(c -> {
            OffsetDateTime u = pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(lojaId, c.id)
                    .map(p -> p.criadoEm).orElse(null);
            return u != null && u.isBefore(hoje0.minusDays(DIAS_SUMIDO)) && u.isAfter(hoje0.minusDays(120));
        }).count();
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
