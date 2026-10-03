package br.com.bora.service;

import br.com.bora.entity.Plano;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** RN09 — aplica os limites do plano contratado pela loja. */
@Service
public class PlanoService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final PedidoRepository pedidos;
    private final br.com.bora.repository.AssinaturaRepository assinaturas;
    private final AsaasClient asaas;
    private final AssinaturaService assinaturaService;

    public PlanoService(LojaRepository lojas, UsuarioRepository usuarios, PedidoRepository pedidos,
                        br.com.bora.repository.AssinaturaRepository assinaturas, AsaasClient asaas,
                        @org.springframework.context.annotation.Lazy AssinaturaService assinaturaService) {
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.pedidos = pedidos;
        this.assinaturas = assinaturas;
        this.asaas = asaas;
        this.assinaturaService = assinaturaService;
    }

    public Plano plano(Long lojaId) {
        return lojas.findById(lojaId)
                .map(l -> l.getPlano() == null ? Plano.UNICO : l.getPlano())
                .orElse(Plano.UNICO);
    }

    /** Resumo do plano da loja: limites e uso atual (para a tela de Planos). */
    public java.util.Map<String, Object> resumo(Long lojaId) {
        Plano p = plano(lojaId);
        OffsetDateTime inicioMes = OffsetDateTime.now(ZONE)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("plano", p.name());
        m.put("maxUsuarios", p.maxUsuarios);
        m.put("maxPedidosMes", p.pedidosIlimitados() ? null : p.maxPedidosMes);
        m.put("usuariosUsados", usuarios.countByLojaIdAndAtivoTrue(lojaId));
        m.put("pedidosMesUsados", pedidos.countByLojaIdAndCriadoEmAfter(lojaId, inicioMes));
        return m;
    }

    public void checarLimiteUsuarios(Long lojaId) {
        Plano p = plano(lojaId);
        if (usuarios.countByLojaIdAndAtivoTrue(lojaId) >= p.maxUsuarios) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite de usuários do plano " + p + " atingido (" + p.maxUsuarios + "). Faça upgrade.");
        }
    }

    /** Troca o plano da loja e, se houver assinatura no Asaas, atualiza o valor cobrado. */
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> trocarPlano(Long lojaId, String novoPlanoStr) {
        Plano novo;
        try {
            novo = Plano.valueOf(novoPlanoStr == null ? "" : novoPlanoStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plano inválido (use UNICO)");
        }
        br.com.bora.entity.Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        loja.setPlano(novo);
        lojas.save(loja);
        assinaturas.findByLojaId(lojaId).ifPresent(a -> {
            a.setPlano(novo);
            assinaturas.save(a);
        });
        // O valor NAO e calculado aqui. Esta classe usava precoEfetivo(), que nao inclui o Modulo IA:
        // um lojista com o add-on ligado chamava este endpoint e derrubava a propria mensalidade de
        // R$ 298 para R$ 199, com a IA seguindo ligada. Quem decide o valor e quem fala com o Asaas e
        // um lugar so, que sempre usa precoComModulos().
        assinaturaService.sincronizarValorComMotivo(lojaId);
        return resumo(lojaId);
    }

    public void checarLimitePedidosMes(Long lojaId) {
        Plano p = plano(lojaId);
        if (p.pedidosIlimitados()) return;
        OffsetDateTime inicioMes = OffsetDateTime.now(ZONE)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        if (pedidos.countByLojaIdAndCriadoEmAfter(lojaId, inicioMes) >= p.maxPedidosMes) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite de pedidos do mês do plano " + p + " atingido (" + p.maxPedidosMes + "). Faça upgrade.");
        }
    }
}
