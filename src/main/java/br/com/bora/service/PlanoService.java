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

    public PlanoService(LojaRepository lojas, UsuarioRepository usuarios, PedidoRepository pedidos) {
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.pedidos = pedidos;
    }

    public Plano plano(Long lojaId) {
        return lojas.findById(lojaId)
                .map(l -> l.getPlano() == null ? Plano.START : l.getPlano())
                .orElse(Plano.START);
    }

    /** Resumo do plano da loja: limites e uso atual (para a tela de Planos). */
    public java.util.Map<String, Object> resumo(Long lojaId) {
        Plano p = plano(lojaId);
        OffsetDateTime inicioMes = OffsetDateTime.now(ZONE)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("plano", p.name());
        m.put("maxUsuarios", p.maxUsuarios);
        m.put("maxPedidosMes", p.maxPedidosMes);
        m.put("usuariosUsados", usuarios.countByLojaId(lojaId));
        m.put("pedidosMesUsados", pedidos.countByLojaIdAndCriadoEmAfter(lojaId, inicioMes));
        return m;
    }

    public void checarLimiteUsuarios(Long lojaId) {
        Plano p = plano(lojaId);
        if (usuarios.countByLojaId(lojaId) >= p.maxUsuarios) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite de usuários do plano " + p + " atingido (" + p.maxUsuarios + "). Faça upgrade.");
        }
    }

    public void checarLimitePedidosMes(Long lojaId) {
        Plano p = plano(lojaId);
        OffsetDateTime inicioMes = OffsetDateTime.now(ZONE)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        if (pedidos.countByLojaIdAndCriadoEmAfter(lojaId, inicioMes) >= p.maxPedidosMes) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite de pedidos do mês do plano " + p + " atingido (" + p.maxPedidosMes + "). Faça upgrade.");
        }
    }
}
