package br.com.bora.controller;

import br.com.bora.dto.EnderecoDaLoja;
import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.EnderecoDaLojaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Os dados de cadastro da loja em que a sessao esta — nome, documento e endereco (V66).
 *
 * <p>Separado de {@code /api/configuracao}, que e marca: cor, logo, nome de exibicao. Mudar de
 * endereco nao e mudar de tema, e quem pode gravar aqui e so o administrador da loja.</p>
 */
@RestController
@RequestMapping("/api/loja")
public class LojaController {

    private final LojaRepository lojas;
    private final AuthContext ctx;
    private final EnderecoDaLojaService enderecos;

    public LojaController(LojaRepository lojas, AuthContext ctx, EnderecoDaLojaService enderecos) {
        this.lojas = lojas;
        this.ctx = ctx;
        this.enderecos = enderecos;
    }

    /** Leitura liberada a quem ja esta na loja: o endereco da propria unidade nao e numero de dono. */
    @GetMapping
    public Map<String, Object> minhaLoja() {
        Loja loja = atual();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", loja.getId());
        r.put("nome", loja.getNome());
        r.put("documento", loja.getDocumento());
        r.put("endereco", EnderecoDaLoja.de(loja));
        r.put("enderecoCadastrado", !EnderecoDaLoja.de(loja).vazio());
        return r;
    }

    @PutMapping("/endereco")
    public Map<String, Object> gravarEndereco(@RequestBody EnderecoDaLoja corpo) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja loja = atual();
        enderecos.aplicar(loja, corpo);
        lojas.save(loja);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lojaId", loja.getId());
        r.put("endereco", EnderecoDaLoja.de(loja));
        return r;
    }

    private Loja atual() {
        Long lojaId = ctx.lojaId();
        return lojas.findById(lojaId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
    }
}
