package br.com.bora.service;

import br.com.bora.dto.EnderecoDaLoja;
import br.com.bora.entity.Loja;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Limpa, confere e aplica o endereco de uma unidade.
 *
 * <p>Mora num servico porque os tres caminhos que gravam endereco sao diferentes e precisam da mesma
 * regra: o cadastro da loja nova (plataforma), a edicao pela plataforma e a edicao pelo proprio
 * lojista. Regra repetida em tres lugares vira tres regras diferentes na terceira vez que alguem
 * mexer.</p>
 */
@Service
public class EnderecoDaLojaService {

    /** As 27 siglas. Lista fechada porque UF errada so aparece na hora de emitir nota. */
    private static final java.util.Set<String> UFS = java.util.Set.of(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG",
            "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    /**
     * Aplica o endereco na loja, do jeito que a tela mandou.
     *
     * <p>Substitui a ficha inteira: campo que veio vazio fica vazio. Ver {@link EnderecoDaLoja}.</p>
     */
    public void aplicar(Loja loja, EnderecoDaLoja e) {
        if (e == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o endereço da loja");
        }
        loja.cep = cep(e.cep());
        loja.logradouro = texto(e.logradouro(), 200, "logradouro");
        loja.numero = texto(e.numero(), 20, "número");
        loja.complemento = texto(e.complemento(), 100, "complemento");
        loja.bairro = texto(e.bairro(), 120, "bairro");
        loja.cidade = texto(e.cidade(), 120, "cidade");
        loja.uf = uf(e.uf());
    }

    private String texto(String v, int limite, String campo) {
        String t = v == null ? null : v.trim();
        if (t == null || t.isEmpty()) return null;
        // Corta-se nada em silencio: a V66 escolheu as larguras para caber o que a tela aceita, e
        // gravar pela metade seria endereco errado passando por endereco certo.
        if (t.length() > limite) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O campo " + campo + " passa de " + limite + " caracteres");
        }
        return t;
    }

    /** Aceita 00000000 ou 00000-000 e grava sempre com hifen. Em branco e um CEP valido: nenhum. */
    private String cep(String v) {
        String t = v == null ? "" : v.replaceAll("\\D", "");
        if (t.isEmpty()) return null;
        if (t.length() != 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CEP precisa de 8 dígitos");
        }
        return t.substring(0, 5) + "-" + t.substring(5);
    }

    private String uf(String v) {
        String t = v == null ? "" : v.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return null;
        if (!UFS.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UF inválida: " + v);
        }
        return t;
    }
}
