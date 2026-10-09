package br.com.bora.controller;

import br.com.bora.entity.Cliente;
import br.com.bora.security.AuthContext;
import br.com.bora.service.ClienteService;
import br.com.bora.service.RepetirPedidoService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService service;
    private final RepetirPedidoService repetir;
    private final AuthContext ctx;

    public ClienteController(ClienteService service, RepetirPedidoService repetir, AuthContext ctx) {
        this.service = service;
        this.repetir = repetir;
        this.ctx = ctx;
    }

    @GetMapping
    public List<Cliente> listar() {
        return service.listar();
    }

    /**
     * Link de "repetir o ultimo pedido" de cada cliente, para a loja mandar no WhatsApp.
     *
     * <p>Numa chamada so, e nao um link por linha do CRM: a tela precisa dos links de todos os
     * clientes de uma vez, e uma chamada por cliente transformaria abrir o CRM em dezenas de
     * requisicoes.</p>
     *
     * <p>Cliente que nunca pediu nesta loja nao aparece no mapa — e so o CRM nao mostrar o botao.</p>
     */
    @GetMapping("/links-repetir")
    public Map<Long, String> linksRepetir(@RequestParam(name = "base", required = false) String base) {
        Long lojaId = ctx.lojaId();
        // A base vem da tela (mesma origem do painel) para o link nascer no dominio certo em
        // producao e em teste. Qualquer coisa que nao seja http(s) e ignorada.
        String origem = (base != null && (base.startsWith("https://") || base.startsWith("http://")))
                ? base.replaceAll("/+$", "") : "https://borahapp.com.br";
        Map<Long, String> m = new LinkedHashMap<>();
        for (Cliente c : service.listar()) {
            repetir.linkDoUltimoPedido(origem, lojaId, c.id).ifPresent(link -> m.put(c.id, link));
        }
        return m;
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
