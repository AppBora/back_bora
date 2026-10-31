package br.com.bora.dto;

/**
 * Endereco de uma unidade. Chega assim do painel (criacao e edicao) e e devolvido assim na leitura.
 *
 * <p>Campo omitido ou em branco significa <b>vazio</b>, nao "mantenha o que estava": a tela manda a
 * ficha inteira, e quem apaga o complemento na tela espera que ele suma. O caminho oposto (ignorar
 * nulo) e o da {@code ConfiguracaoLoja}, onde cada campo tem a sua propria tela.</p>
 */
public record EnderecoDaLoja(
        String cep,
        String logradouro,
        String numero,
        String complemento,
        String bairro,
        String cidade,
        String uf) {

    public static EnderecoDaLoja de(br.com.bora.entity.Loja loja) {
        return new EnderecoDaLoja(loja.cep, loja.logradouro, loja.numero,
                loja.complemento, loja.bairro, loja.cidade, loja.uf);
    }

    /** Vazio = nenhum campo preenchido. Serve para a tela dizer "falta cadastrar o endereco". */
    public boolean vazio() {
        return cep == null && logradouro == null && numero == null && complemento == null
                && bairro == null && cidade == null && uf == null;
    }
}
