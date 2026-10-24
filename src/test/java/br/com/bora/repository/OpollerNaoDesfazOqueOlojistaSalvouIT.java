package br.com.bora.repository;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O ciclo de polling grava só as colunas dele — e não desfaz o que o lojista acabou de salvar.
 *
 * <h2>O defeito que isto fecha (item 5.5)</h2>
 * <p>O ciclo carrega as integrações no início ({@code findByAtivoTrue}, fora de transação) e grava
 * {@code repo.save(i)} até <b>25 segundos</b> depois — a linha <b>inteira</b>, com os valores velhos
 * de todas as colunas. O que o lojista salvasse nesse meio tempo era desfeito sem aviso: desligar a
 * integração, tirar o aceite automático, colar uma credencial nova. Ele salvava, via "salvo" na tela,
 * e o valor voltava sozinho no ciclo seguinte — o tipo de defeito que faz o lojista achar que o
 * sistema "não salva" e desistir de mexer.</p>
 *
 * <h2>Por que contra Postgres de verdade</h2>
 * <p>Com mock eu provaria que chamei outro método. O que precisa ser provado é <b>o que ficou na
 * linha</b>, coluna por coluna — e isso só o banco responde. O primeiro teste aqui prova a
 * <b>premissa</b>: com {@code save} da entidade velha, a edição do lojista realmente desaparece. Se
 * ele parar de falhar, o conserto ficou obsoleto e este arquivo todo pode sair.</p>
 *
 * <p>Como rodar: ver o javadoc do {@link PreviaDoAcertoBateComOPagamentoIT}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OpollerNaoDesfazOqueOlojistaSalvouIT {

    @Autowired private IntegracaoCanalRepository repo;
    @Autowired private LojaRepository lojas;
    @Autowired private EntityManager em;

    private Long loja;
    private Long outraLoja;
    private Long integracaoId;

    @BeforeEach
    void montar() {
        loja = criarLoja("Loja do polling (teste)");
        outraLoja = criarLoja("Outra loja (teste)");

        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = loja;
        i.canal = "IFOOD";
        i.ativo = true;
        i.autoAceitar = true;
        i.status = "ERRO";
        i.merchantId = "merchant-da-loja";
        i.clientSecret = "segredo-velho";
        i.ultimoErro = "o erro de ontem";
        integracaoId = repo.save(i).id;
        limpar();
    }

    private Long criarLoja(String nome) {
        Loja l = new Loja();
        l.setNome(nome);
        l.setAtivo(true);
        return lojas.save(l).getId();
    }

    /** Força a ida ao banco: {@code @Modifying} não mexe no cache de primeiro nível. */
    private void limpar() {
        em.flush();
        em.clear();
    }

    /** A linha como está no banco agora. */
    private IntegracaoCanal doBanco() {
        limpar();
        return repo.findById(integracaoId).orElseThrow();
    }

    /** A cópia que o ciclo carregou no início, com os valores daquele momento. */
    private IntegracaoCanal copiaDoCicloDePolling() {
        IntegracaoCanal velha = doBanco();
        em.detach(velha);
        return velha;
    }

    /** O lojista mexeu na tela de Integrações enquanto o ciclo rodava. */
    private void oLojistaSalvou() {
        IntegracaoCanal atual = doBanco();
        atual.ativo = false;                      // desligou a integração
        atual.autoAceitar = false;                // tirou o aceite automático
        atual.clientSecret = "segredo-novo";      // colou uma credencial nova
        atual.merchantId = "merchant-corrigido";
        repo.save(atual);
        limpar();
    }

    // =============================================================== a premissa do item 5.5

    @Test
    void comSAVEdaLINHAinteira_aEDICAOdoLOJISTAdesaparece() {
        // Isto é o defeito, provado. Não é o comportamento desejado: é o que havia.
        IntegracaoCanal velha = copiaDoCicloDePolling();
        oLojistaSalvou();

        velha.ultimoPollingEm = OffsetDateTime.now();   // o que o polling queria gravar
        repo.save(velha);

        IntegracaoCanal depois = doBanco();
        assertEquals(Boolean.TRUE, depois.ativo,
                "a premissa do item 5.5: o save da linha inteira RELIGA a integracao que o lojista "
                        + "desligou. Se este assert falhar, o defeito nao existe mais por outro "
                        + "caminho e este arquivo pode sair");
        assertEquals(Boolean.TRUE, depois.autoAceitar);
        assertEquals("merchant-da-loja", depois.merchantId);
    }

    // =============================================================== o conserto

    @Test
    void oPOLLINGqueFUNCIONOU_naoENCOSTAnoQUEoLOJISTAsalvou() {
        IntegracaoCanal velha = copiaDoCicloDePolling();
        oLojistaSalvou();

        repo.marcarPollingOk(velha.id, velha.lojaId, OffsetDateTime.now());

        IntegracaoCanal depois = doBanco();
        assertEquals(Boolean.FALSE, depois.ativo, "a integracao tem que continuar DESLIGADA");
        assertEquals(Boolean.FALSE, depois.autoAceitar, "e sem aceite automatico");
        assertEquals("segredo-novo", depois.clientSecret, "e com a credencial nova");
        assertEquals("merchant-corrigido", depois.merchantId);
    }

    @Test
    void eAINDAgravaOqueOpollingTEMqueGRAVAR() {
        // O outro lado: preservar tudo gravando nada seria igualmente errado.
        IntegracaoCanal velha = copiaDoCicloDePolling();

        repo.marcarPollingOk(velha.id, velha.lojaId, OffsetDateTime.now());

        IntegracaoCanal depois = doBanco();
        assertNotNull(depois.ultimoPollingEm, "a hora da tentativa tem que ficar");
        assertNull(depois.ultimoErro, "e o erro antigo tem que sair da tela");
        assertEquals("CONECTADO", depois.status, "e a loja tem que sair do ERRO (item 5.1)");
        assertTrue(depois.prontaParaSincronizar(), "voltando para a fila de 30 segundos");
    }

    @Test
    void oPOLLINGqueFALHOU_soANOTAaHORAeOmOTIVO() {
        IntegracaoCanal velha = copiaDoCicloDePolling();
        oLojistaSalvou();

        repo.marcarPollingFalhou(velha.id, velha.lojaId, OffsetDateTime.now(), "o iFood nao respondeu");

        IntegracaoCanal depois = doBanco();
        assertEquals("o iFood nao respondeu", depois.ultimoErro);
        assertNotNull(depois.ultimoPollingEm);
        assertEquals(Boolean.FALSE, depois.ativo);
        assertEquals("segredo-novo", depois.clientSecret);
    }

    @Test
    void oRECADOnaoMARCAerro_eNAOmexeEMmaisNADA() {
        // marcarAviso existe para o status que o marketplace recusou (item 5.2). Marcar ERRO ali
        // jogaria a loja na fila de 10 minutos do item 5.1.
        repo.marcarPollingOk(integracaoId, loja, OffsetDateTime.now());   // deixa CONECTADO
        limpar();

        repo.marcarAviso(integracaoId, loja, "o 99Food nao aceitou o status PRONTO",
                OffsetDateTime.now());

        IntegracaoCanal depois = doBanco();
        assertEquals("o 99Food nao aceitou o status PRONTO", depois.ultimoAviso);
        assertNotNull(depois.ultimoAvisoEm, "recado sem hora nao da para usar");
        assertNull(depois.ultimoErro, "o recado nao e saude de integracao e nao pode ir nessa coluna");
        assertEquals("CONECTADO", depois.status, "recado NAO e erro de integracao");
        assertTrue(depois.prontaParaSincronizar());
    }

    @Test
    void oPOLLINGqueFUNCIONAnaoAPAGAoRECADO_contraPOSTGRES() {
        // O defeito da V61, medido contra o banco de verdade e nao contra um dublê. Era aqui que ele
        // vivia: `marcarPollingOk` faz `ultimo_erro = null` sem condicao, e roda a cada 30 segundos.
        // Com o recado morando naquela coluna, o lojista tinha ate meio minuto para ve-lo.
        repo.marcarAviso(integracaoId, loja, "o 99Food nao aceitou o status PRONTO do pedido 1234",
                OffsetDateTime.now());
        repo.marcarPollingFalhou(integracaoId, loja, OffsetDateTime.now(), "timeout no polling");
        limpar();

        repo.marcarPollingOk(integracaoId, loja, OffsetDateTime.now());

        IntegracaoCanal depois = doBanco();
        assertNull(depois.ultimoErro, "o polling que volta a funcionar TEM de limpar a saude");
        assertEquals("o 99Food nao aceitou o status PRONTO do pedido 1234", depois.ultimoAviso,
                "o polling apagou o recado: o pedido continua parado no marketplace e o lojista "
                        + "perdeu a unica tela que dizia isso");
    }

    @Test
    void SOoLIMPARAVISOapagaORECADO_contraPOSTGRES() {
        repo.marcarAviso(integracaoId, loja, "recado qualquer", OffsetDateTime.now());
        limpar();

        repo.limparAviso(integracaoId, loja);

        IntegracaoCanal depois = doBanco();
        assertNull(depois.ultimoAviso);
        assertNull(depois.ultimoAvisoEm, "a hora tem de sair junto, senao sobra data sem recado");
    }

    // =============================================================== o status que nao pode ser promovido

    @Test
    void aguardandoAUTORIZACAOnaoViraCONECTADO() {
        // Aqui falta o lojista digitar o codigo no portal do iFood. Dizer CONECTADO prometeria no
        // painel um pedido que nunca vem.
        IntegracaoCanal i = doBanco();
        i.status = "AGUARDANDO_AUTORIZACAO";
        repo.save(i);
        limpar();

        repo.marcarPollingOk(integracaoId, loja, OffsetDateTime.now());

        assertEquals("AGUARDANDO_AUTORIZACAO", doBanco().status);
    }

    @Test
    void quemJAestaCONECTADOcontinuaCONECTADO() {
        repo.marcarPollingOk(integracaoId, loja, OffsetDateTime.now());
        limpar();

        repo.marcarPollingOk(integracaoId, loja, OffsetDateTime.now());

        assertEquals("CONECTADO", doBanco().status);
    }

    // =============================================================== o contador

    @Test
    void doisPEDIDOSnoMESMOciclo_contamDOIS() {
        // Era leitura-soma-escrita em cima de um valor carregado no inicio do ciclo: o segundo pedido
        // gravava o mesmo numero que o primeiro, e o contador da tela ficava para tras.
        IntegracaoCanal velha = copiaDoCicloDePolling();
        assertEquals(0, velha.pedidosRecebidos);

        repo.contarPedidoRecebido(velha.id, velha.lojaId, OffsetDateTime.now());
        repo.contarPedidoRecebido(velha.id, velha.lojaId, OffsetDateTime.now());

        IntegracaoCanal depois = doBanco();
        assertEquals(2, depois.pedidosRecebidos, "a soma e do banco, nao do valor que o ciclo leu");
        assertNotNull(depois.ultimaSync);
    }

    @Test
    void oCONTADORnaoDESFAZoQUEoLOJISTAsalvou() {
        IntegracaoCanal velha = copiaDoCicloDePolling();
        oLojistaSalvou();

        repo.contarPedidoRecebido(velha.id, velha.lojaId, OffsetDateTime.now());

        assertEquals(Boolean.FALSE, doBanco().ativo);
        assertEquals(1, doBanco().pedidosRecebidos);
    }

    @Test
    void oCONTADORnaoPODEserNULOnoBANCO() {
        // O banco me corrigiu aqui. Eu pus `coalesce(pedidos_recebidos, 0) + 1` na consulta porque o
        // campo da entidade e um Integer (aceita null), e `null + 1` e null em SQL -- o contador
        // ficaria preso em nulo para sempre. Fui escrever o teste do caso nulo e descobri que a
        // COLUNA e NOT NULL: o caso nao existe, e o coalesce e cinto de seguranca.
        //
        // Deixo medido em vez de deixar escrito: se alguem tornar a coluna nullable, este teste
        // falha e conta por que o coalesce esta lá.
        var erro = assertThrows(Exception.class, () -> {
            em.createNativeQuery("update integracao_canal set pedidos_recebidos = null where id = ?1")
                    .setParameter(1, integracaoId).executeUpdate();
            em.flush();
        });

        assertTrue(erro.toString().contains("pedidos_recebidos"),
                "esperava a recusa da coluna NOT NULL; foi: " + erro);
    }

    // =============================================================== o filtro de loja

    @Test
    void comAlojaERRADAnaoESCREVEnada() {
        // O `and i.lojaId = :lojaId` nao e decoracao: id trocado por engano vira UPDATE de zero
        // linhas, em vez de escrever na integracao de outra loja.
        int linhas = repo.marcarErro(integracaoId, outraLoja, "erro que nao e desta loja");

        assertEquals(0, linhas, "nao pode ter escrito em nenhuma linha");
        assertEquals("o erro de ontem", doBanco().ultimoErro, "e nada pode ter mudado");
        assertEquals("ERRO", doBanco().status);
    }

    @Test
    void comAlojaCERTAescreveUMAlinhaSO() {
        assertEquals(1, repo.marcarErro(integracaoId, loja, "a credencial foi recusada"));
        assertEquals("a credencial foi recusada", doBanco().ultimoErro);
        assertEquals("ERRO", doBanco().status);
    }
}
