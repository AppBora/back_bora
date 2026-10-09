package br.com.bora.controller;

import br.com.bora.entity.Cliente;
import br.com.bora.security.AuthContext;
import br.com.bora.service.ClienteService;
import br.com.bora.service.RepetirPedidoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService service;
    private final RepetirPedidoService repetir;
    private final AuthContext ctx;
    private final String basePublica;

    public ClienteController(ClienteService service, RepetirPedidoService repetir, AuthContext ctx,
                             @Value("${bora.url-publica:https://borahapp.com.br}") String basePublica) {
        this.service = service;
        this.repetir = repetir;
        this.ctx = ctx;
        this.basePublica = basePublica.replaceAll("/+$", "");
    }

    @GetMapping
    public List<Cliente> listar() {
        return service.listar();
    }

    /**
     * Link de "repetir o ultimo pedido" de cada cliente, para a loja mandar no WhatsApp.
     *
     * <p>Numa consulta so, e nao um link por linha do CRM: abrir a tela de uma loja com 500 clientes
     * chegou a custar 501 idas ao banco.</p>
     *
     * <p>O endereco do link vem da <b>configuracao do servidor</b>, nao do navegador. Antes a tela
     * mandava o proprio {@code location.origin}: bastava alguem abrir o painel por um tunel de teste
     * para os links enviados aos clientes saírem apontando para aquele endereco — com assinatura
     * valida.</p>
     *
     * <p>Cliente que nunca pediu, ou cujo ultimo pedido nao e repetivel (cancelado, ou vindo de
     * marketplace), nao aparece no mapa: e so o CRM nao mostrar o botao.</p>
     */
    @GetMapping("/links-repetir")
    public Map<Long, String> linksRepetir() {
        return repetir.linksDosUltimosPedidos(basePublica, ctx.lojaId());
    }

    @PostMapping
    public Cliente criar(@RequestBody Cliente c) {
        return service.criar(c);
    }

    @PutMapping("/{id}")
    public Cliente atualizar(@PathVariable Long id, @RequestBody Cliente c) {
        return service.atualizar(id, c);
    }

    @DeleteMapping("/{id}")
    public void excluir(@PathVariable Long id) {
        service.excluir(id);
    }
}
