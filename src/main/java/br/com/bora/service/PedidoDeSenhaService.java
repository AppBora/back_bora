package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.PedidoDeSenha;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoDeSenhaRepository;
import br.com.bora.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * "Esqueci minha senha": o pedido chega a quem pode resolver.
 *
 * <h2>Por que não é um link mágico por e-mail</h2>
 * <p>O sistema não envia e-mail. Nenhum. Então o caminho honesto é o pedido chegar ao administrador
 * da loja, que redefine a senha na tela de Usuários.</p>
 *
 * <h2>Por que o WhatsApp não resolve sozinho hoje</h2>
 * <p>Medido em 18/10/2026 contra a API da Meta, com as credenciais que estão no banco: a da loja 1
 * respondeu <i>"Session has expired on 08-Jul-26"</i> e a da loja 18 tem 9 caracteres — é um
 * texto de exemplo, não um token. <b>Nenhuma loja consegue enviar mensagem hoje.</b> Por isso o que
 * garante o aviso é o registro no banco, que a tela de Usuários mostra. O envio por WhatsApp é
 * tentado e, quando a conta da Meta existir, passa a sair sozinho — {@code avisadoEm} diz se saiu.</p>
 *
 * <h2>Por que a resposta é sempre igual</h2>
 * <p>O endpoint é público. Se respondesse diferente para e-mail que existe e e-mail que não existe,
 * a tela de login viraria um verificador de clientes: dava para descobrir quem usa o BoraHapp
 * digitando endereços. Então registra quando dá, e responde a mesma coisa sempre.</p>
 */
@Slf4j
@Service
public class PedidoDeSenhaService {

    /** Teto de pedidos por pessoa numa hora. Acima disso o clique não vira linha nova. */
    private static final int PEDIDOS_POR_HORA = 3;

    private final PedidoDeSenhaRepository pedidos;
    private final UsuarioRepository usuarios;
    private final LojaRepository lojas;
    private final IntegracaoCanalRepository integracoes;
    private final WhatsAppSender whatsapp;
    private final String whatsappSuporte;

    public PedidoDeSenhaService(PedidoDeSenhaRepository pedidos, UsuarioRepository usuarios,
                                LojaRepository lojas, IntegracaoCanalRepository integracoes,
                                WhatsAppSender whatsapp,
                                @Value("${bora.suporte.whatsapp:}") String whatsappSuporte) {
        this.pedidos = pedidos;
        this.usuarios = usuarios;
        this.lojas = lojas;
        this.integracoes = integracoes;
        this.whatsapp = whatsapp;
        this.whatsappSuporte = whatsappSuporte;
    }

    /**
     * Registra o pedido de quem esqueceu a senha. Não devolve nada por decisão: quem chama responde
     * sempre a mesma coisa, exista o e-mail ou não.
     */
    @Transactional
    public void registrar(String email) {
        String limpo = email == null ? "" : email.trim().toLowerCase();
        if (limpo.isEmpty()) return;
        Usuario u = usuarios.findByEmail(limpo).filter(Usuario::getAtivo).orElse(null);
        if (u == null) {
            // Nada a fazer, e nada a gravar: a tabela nao e deposito de e-mail digitado.
            return;
        }

        // Clicar de novo nao abre outro pedido: o administrador veria a mesma pessoa tres vezes na
        // lista e nao saberia se sao tres pessoas ou tres cliques.
        Optional<PedidoDeSenha> aberto = pedidos.findFirstByUsuarioIdAndAtendidoEmIsNull(u.getId());
        if (aberto.isPresent()) {
            avisar(u, aberto.get());
            return;
        }
        if (pedidos.countByUsuarioIdAndCriadoEmAfter(u.getId(), OffsetDateTime.now().minusHours(1))
                >= PEDIDOS_POR_HORA) {
            log.info("Pedido de senha do usuario {} ignorado: passou de {} na ultima hora",
                    u.getId(), PEDIDOS_POR_HORA);
            return;
        }

        PedidoDeSenha p = new PedidoDeSenha();
        p.usuarioId = u.getId();
        p.lojaId = u.getLojaId();
        p.criadoEm = OffsetDateTime.now();
        pedidos.save(p);
        log.info("Pedido de senha registrado: usuario {} da loja {}", u.getId(), u.getLojaId());
        avisar(u, p);
    }

    /**
     * Tenta o aviso por WhatsApp. Hoje nao sai de lugar nenhum, e isso esta registrado em
     * {@code avisadoEm}: nulo quer dizer que ninguem foi avisado por mensagem, so pelo painel.
     */
    private void avisar(Usuario u, PedidoDeSenha p) {
        if (p.avisadoEm != null) return;
        IntegracaoCanal zap = u.getLojaId() == null ? null
                : integracoes.findByLojaIdAndCanal(u.getLojaId(), "WHATSAPP").orElse(null);
        if (zap == null || !Boolean.TRUE.equals(zap.ativo)) {
            log.info("Pedido de senha {}: sem WhatsApp conectado na loja {} — o aviso fica so no painel",
                    p.id, u.getLojaId());
            return;
        }
        Loja loja = u.getLojaId() == null ? null : lojas.findById(u.getLojaId()).orElse(null);
        String texto = "BoraHapp: " + u.getNome() + " (" + u.getEmail() + ") esqueceu a senha e pediu"
                + " ajuda. Para redefinir: painel > Usuarios > Redefinir senha.";
        boolean saiu = false;
        if (loja != null && loja.whatsappDono != null && !loja.whatsappDono.isBlank()) {
            saiu = whatsapp.enviar(zap, loja.whatsappDono, texto);
        }
        // O suporte da plataforma tambem recebe: e quem o lojista procura quando o proprio
        // administrador e que perdeu a senha.
        if (whatsappSuporte != null && !whatsappSuporte.isBlank()) {
            saiu = whatsapp.enviar(zap, whatsappSuporte, texto) || saiu;
        }
        if (saiu) {
            p.avisadoEm = OffsetDateTime.now();
            pedidos.save(p);
        } else {
            log.warn("Pedido de senha {}: o WhatsApp da loja {} nao aceitou o envio — aviso so no painel",
                    p.id, u.getLojaId());
        }
    }

    /** O que o administrador daquela loja ainda não resolveu. */
    @Transactional(readOnly = true)
    public List<PedidoDeSenha> emAberto(Long lojaId) {
        if (lojaId == null) return List.of();
        return pedidos.findByLojaIdAndAtendidoEmIsNullOrderByCriadoEmDesc(lojaId);
    }

    /**
     * Fecha o pedido de quem acabou de ter a senha redefinida.
     *
     * <p>Chamado pelo {@code UsuarioService.definirSenha}: o aviso tem que sumir pela AÇÃO que
     * resolve, não por um botão separado de "marcar como resolvido" que todo mundo esquece.</p>
     */
    @Transactional
    public void atendido(Long usuarioId) {
        List<PedidoDeSenha> abertos = pedidos.findByUsuarioIdAndAtendidoEmIsNull(usuarioId);
        for (PedidoDeSenha p : abertos) p.atendidoEm = OffsetDateTime.now();
        if (!abertos.isEmpty()) pedidos.saveAll(abertos);
    }
}
