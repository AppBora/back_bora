package br.com.bora.service;

import br.com.bora.dto.EnderecoDaLoja;
import br.com.bora.entity.Loja;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O endereco da loja (V66) nasceu com tres portas que gravam a mesma ficha: a criacao da loja pela
 * plataforma, a edicao pela plataforma e a edicao pelo lojista. Este teste prende a regra no
 * servico — e prende tambem a FIACAO, porque regra certa que nenhum controller chama e defeito com
 * teste verde (a licao da varredura de 23/10: sete endpoints prontos que tela nenhuma usava).
 */
class OenderecoDaLojaEhConferidoAntesDeGravarTest {

    private final EnderecoDaLojaService servico = new EnderecoDaLojaService();

    private EnderecoDaLoja zira(String cep, String uf) {
        return new EnderecoDaLoja(cep, "Rua Firmino Mineli", "751", null,
                "Vila Mineirão", "Sorocaba", uf);
    }

    @Test
    void grava_a_ficha_inteira_e_normaliza_cep_e_uf() {
        Loja loja = new Loja();
        servico.aplicar(loja, zira("18087000", "sp"));

        assertEquals("18087-000", loja.cep, "o CEP e gravado sempre com hifen");
        assertEquals("SP", loja.uf, "a UF e gravada em maiusculas");
        assertEquals("Rua Firmino Mineli", loja.logradouro);
        assertEquals("751", loja.numero);
        assertEquals("Vila Mineirão", loja.bairro);
        assertEquals("Sorocaba", loja.cidade);
        assertNull(loja.complemento, "complemento em branco fica vazio, nao em branco");
    }

    @Test
    void campo_apagado_na_tela_e_apagado_no_banco() {
        Loja loja = new Loja();
        servico.aplicar(loja, new EnderecoDaLoja("18087-000", "Rua Antenor Maciel", "393", "Loja 2",
                "Jardim Tropical", "Sorocaba", "SP"));
        assertEquals("Loja 2", loja.complemento);

        // A ficha vem inteira da tela. Quem apaga o complemento espera que ele suma -- este e o
        // ponto em que o endereco se comporta DIFERENTE da ConfiguracaoLoja, que ignora nulo.
        servico.aplicar(loja, new EnderecoDaLoja("18087-000", "Rua Antenor Maciel", "393", "  ",
                "Jardim Tropical", "Sorocaba", "SP"));
        assertNull(loja.complemento);
    }

    @Test
    void endereco_vazio_e_valido_porque_ninguem_informou_ainda() {
        Loja loja = new Loja();
        servico.aplicar(loja, new EnderecoDaLoja(null, null, null, null, null, null, null));
        assertTrue(EnderecoDaLoja.de(loja).vazio());
        assertNull(loja.cep);
        assertNull(loja.uf);
    }

    @Test
    void cep_com_menos_de_oito_digitos_para_antes_de_gravar() {
        Loja loja = new Loja();
        loja.cep = "18087-000";
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servico.aplicar(loja, zira("1808700", "SP")));
        assertTrue(e.getMessage().contains("8 dígitos"), e.getMessage());
    }

    @Test
    void uf_que_nao_existe_para_antes_de_gravar() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servico.aplicar(new Loja(), zira("18087-000", "XX")));
        assertTrue(e.getMessage().contains("UF inválida"), e.getMessage());
    }

    /**
     * A v126 perdeu um pedido porque o texto era maior que a coluna e o {@code validate} do
     * Hibernate nao confere largura. Aqui o corte nao acontece em silencio: a gravacao para.
     */
    @Test
    void texto_maior_que_a_coluna_nao_entra_cortado() {
        String rua = "R".repeat(201);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servico.aplicar(new Loja(), new EnderecoDaLoja(null, rua, "1", null,
                        "Centro", "Sorocaba", "SP")));
        assertTrue(e.getMessage().contains("logradouro"), e.getMessage());
    }

    @Test
    void as_tres_portas_que_gravam_endereco_passam_por_este_servico() throws IOException {
        String plataforma = Files.readString(
                Path.of("src/main/java/br/com/bora/controller/PlataformaController.java"));
        String daLoja = Files.readString(
                Path.of("src/main/java/br/com/bora/controller/LojaController.java"));

        assertTrue(plataforma.contains("enderecos.aplicar(loja, req.endereco())"),
                "a criacao de loja precisa aplicar o endereco pela mesma regra");
        assertTrue(plataforma.contains("@PutMapping(\"/lojas/{lojaId}/endereco\")"),
                "falta a porta da plataforma para editar endereco");
        assertTrue(daLoja.contains("@PutMapping(\"/endereco\")")
                        && daLoja.contains("enderecos.aplicar(loja, corpo)"),
                "falta a porta do lojista, ou ela grava sem passar pela regra");
        assertTrue(daLoja.contains("ctx.requirePapel(\"ADMINISTRADOR_LOJA\")"),
                "quem nao administra a loja nao muda o endereco dela");
    }

    /** A largura da coluna no banco tem de bater com o limite que o servico cobra. */
    @Test
    void a_migracao_declara_as_larguras_que_o_servico_cobra() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V66__endereco_da_loja.sql"));
        assertTrue(sql.contains("logradouro  VARCHAR(200)"), sql);
        assertTrue(sql.contains("bairro      VARCHAR(120)"),
                "bairro espelha taxa_entrega.bairro (120)");
        assertTrue(sql.contains("cidade      VARCHAR(120)"), sql);
        assertTrue(sql.contains("numero      VARCHAR(20)"), sql);
        assertTrue(sql.contains("complemento VARCHAR(100)"), sql);
        assertTrue(sql.contains("cep         VARCHAR(9)"), sql);
        assertTrue(sql.contains("uf          VARCHAR(2)"), sql);
    }
}
