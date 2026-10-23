package br.com.bora.controller;

import br.com.bora.security.AuthContext;
import br.com.bora.service.VendasSaudeService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vendas")
public class VendasController {

    private final VendasSaudeService saude;
    private final AuthContext ctx;

    public VendasController(VendasSaudeService saude, AuthContext ctx) {
        this.saude = saude;
        this.ctx = ctx;
    }

    /**
     * Termometro de vendas: faturamento das ultimas 24h e a media esperada, em reais.
     *
     * <h2>Item 8.3 da varredura</h2>
     * <p>Isto nao tinha checagem de papel nenhuma, e o Dashboard — que o <b>balconista</b> abre —
     * desenha os dois valores em destaque. Era o faturamento da loja na tela de quem atende o balcao,
     * enquanto Relatorios, Desempenho e Rede, que mostram a mesma coisa, todos exigem papel.</p>
     *
     * <p>Aqui a acao inteira e numero de dono, entao bloqueia mesmo (403). A tela ja trata: o
     * Dashboard do operador passou a nao desenhar o painel, em vez de mostrar erro.</p>
     */
    @GetMapping("/termometro")
    public Map<String, Object> termometro() {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        return saude.termometro(ctx.lojaId());
    }
}
