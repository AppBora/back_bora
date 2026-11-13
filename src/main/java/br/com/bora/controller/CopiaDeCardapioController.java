package br.com.bora.controller;

import br.com.bora.security.AuthContext;
import br.com.bora.service.CopiaDeCardapioService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Copiar o cardapio de uma unidade para outra do mesmo cliente.
 *
 * <p>Controller proprio, e nao mais um metodo no {@code PlataformaController}: aquele ja passa de
 * mil linhas e tem quatro testes que o constroem na mao -- cada parametro novo no construtor dele
 * quebra os quatro por um motivo que nada tem a ver com o que eles medem.</p>
 *
 * <p>A regra de quem pode esta no {@code requireAdminBora}; a regra de <b>quais lojas</b> esta no
 * servico (mesma empresa, destino vazio), porque e regra de negocio e tem de valer por qualquer
 * caminho que chame o servico, nao so por este.</p>
 */
@RestController
@RequestMapping("/admin-bora")
public class CopiaDeCardapioController {

    private final CopiaDeCardapioService copias;
    private final AuthContext ctx;

    public CopiaDeCardapioController(CopiaDeCardapioService copias, AuthContext ctx) {
        this.copias = copias;
        this.ctx = ctx;
    }

    @PostMapping("/lojas/{lojaId}/copiar-cardapio")
    public Map<String, Object> copiar(@PathVariable Long lojaId, @RequestBody Map<String, Object> corpo) {
        ctx.requireAdminBora();
        Object bruto = corpo == null ? null : corpo.get("origemLojaId");
        if (bruto == null || String.valueOf(bruto).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe a loja de origem (origemLojaId) — de qual unidade copiar o cardapio.");
        }
        long origem;
        try {
            origem = Long.parseLong(String.valueOf(bruto).trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "origemLojaId precisa ser o numero da loja de origem.");
        }
        return copias.copiar(origem, lojaId);
    }
}
