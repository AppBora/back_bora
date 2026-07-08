package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * OPERAÇÃO ASSISTIDA (parte do Módulo IA): avisa o CLIENTE no WhatsApp a cada fase do pedido,
 * com link da página pública de acompanhamento. Requer loja com Módulo IA + integração WHATSAPP
 * ativa e telefone do cliente no pedido. Nunca lança exceção (não pode travar a cozinha).
 */
@Slf4j
@Service
public class NotificacaoClienteService {

    private final LojaRepository lojas;
    private final ClienteRepository clientes;
    private final IntegracaoCanalRepository integracoes;
    private final WhatsAppSender whats;

    public NotificacaoClienteService(LojaRepository lojas, ClienteRepository clientes,
                                     IntegracaoCanalRepository integracoes, WhatsAppSender whats) {
        this.lojas = lojas;
        this.clientes = clientes;
        this.integracoes = integracoes;
        this.whats = whats;
    }

    public void notificarFase(Pedido p, StatusPedido status) {
        try {
            Loja loja = lojas.findById(p.lojaId).orElse(null);
            if (loja == null || !Boolean.TRUE.equals(loja.moduloIa)) return; // add-on não contratado
            IntegracaoCanal zap = integracoes.findByLojaIdAndCanal(p.lojaId, "WHATSAPP")
                    .filter(i -> Boolean.TRUE.equals(i.ativo)).orElse(null);
            if (zap == null) return;
            String tel = p.clienteTelefone;
            if ((tel == null || tel.isBlank()) && p.clienteId != null) {
                tel = clientes.findById(p.clienteId).map(c -> c.telefone).orElse(null);
            }
            if (tel == null || tel.replaceAll("\\D", "").length() < 10) return;

            String cod = p.codigo == null ? String.valueOf(p.id) : p.codigo;
            String link = "https://borahapp.com.br/acompanhar.html?loja=" + p.lojaId + "&pedido=" + p.id;
            String texto = switch (status) {
                case CONFIRMADO -> "✅ *" + loja.nome + "*: seu pedido " + cod + " foi confirmado! Já vamos começar o preparo.";
                case EM_PREPARO -> "👨‍🍳 *" + loja.nome + "*: seu pedido " + cod + " está sendo preparado!";
                case PRONTO -> "📦 *" + loja.nome + "*: pedido " + cod + " prontinho!";
                case SAIU_PARA_ENTREGA -> "🛵 *" + loja.nome + "*: seu pedido " + cod + " saiu para entrega! Já já chega aí.";
                case ENTREGUE -> "🎉 *" + loja.nome + "*: pedido " + cod + " entregue. Bom apetite e obrigado pela preferência!";
                case CANCELADO -> "😔 *" + loja.nome + "*: seu pedido " + cod + " foi cancelado"
                        + (p.motivoCancelamento == null ? "." : " (" + p.motivoCancelamento + ").")
                        + " Qualquer dúvida, chame a gente por aqui.";
                default -> null;
            };
            if (texto == null) return;
            if (status != StatusPedido.ENTREGUE && status != StatusPedido.CANCELADO) {
                texto += "\n\nAcompanhe ao vivo: " + link;
            }
            whats.enviar(zap, tel, texto);
        } catch (Exception e) {
            log.warn("Operação Assistida loja {}: falha ao notificar pedido {}: {}", p.lojaId, p.id, e.getMessage());
        }
    }
}
