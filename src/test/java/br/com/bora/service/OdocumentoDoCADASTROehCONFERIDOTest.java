package br.com.bora.service;

import br.com.bora.dto.SignupRequest;
import br.com.bora.entity.Empresa;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.EmpresaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.FreioDeTentativas;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ITEM 4 DA SECAO 4 DA VARREDURA (confirmado em 19/11/2026, consertado em 20/11).
 *
 * <p>O cadastro publico aceitava <b>qualquer texto</b> como CPF/CNPJ. O que isso custava:</p>
 * <ul>
 *   <li>"mesmo CNPJ = mesma empresa" e a regra que monta a rede multi-lojas. Dois cadastros com
 *       {@code 00000000000000} viravam a MESMA empresa -- duas lojas de clientes diferentes dentro
 *       de um cliente so, uma a um clique da outra pelo seletor de loja;</li>
 *   <li>o documento do cadastro vai para a FATURA (Asaas) e para a NOTA FISCAL. Invalido ali e
 *       cobranca que nao nasce, descoberta no dia do vencimento.</li>
 * </ul>
 *
 * <p><b>O teste do CNPJ alfanumerico nao e exercicio de futuro.</b> A regra vale desde julho de 2026
 * (IN RFB 2.229/2024): as 12 primeiras posicoes podem ter letra. Um validador so-de-digito recusaria
 * cliente novo legitimo, e {@code replaceAll("\\D","")} mutila o documento em silencio. O numero
 * usado aqui e o exemplo publicado pela Receita/Serpro -- se a aritmetica desta classe estiver
 * errada, este teste cai.</p>
 */
class OdocumentoDoCADASTROehCONFERIDOTest {

    // Documentos de teste publicos, de ninguem: validos pelo digito verificador.
    private static final String CNPJ_BOM = "11.222.333/0001-81";
    private static final String CPF_BOM = "529.982.247-25";
    /** O exemplo oficial do CNPJ alfanumerico. Base "12ABC34501DE", DV "35". */
    private static final String CNPJ_ALFA = "12.ABC.345/01DE-35";

    // =================================================================== a premissa

    // SEM ISTO, tudo abaixo passaria com um validador que recusa o mundo -- e nenhuma loja nova
    // conseguiria se cadastrar, com a suite verde.
    @Test
    void premissa_documentoDEverdadePASSA() {
        assertTrue(DocumentoFiscal.valido(CNPJ_BOM), "CNPJ valido tem de passar");
        assertTrue(DocumentoFiscal.valido(CPF_BOM), "CPF valido tem de passar");
        assertTrue(DocumentoFiscal.valido(null), "em branco passa: loja sem documento ja existe hoje");
        assertTrue(DocumentoFiscal.valido("   "), "so espaco e o mesmo que em branco");
    }

    // =================================================================== o que era aceito

    @Test
    void oDOCUMENTOdeLIXOnaoPASSAmais() {
        assertFalse(DocumentoFiscal.valido("00000000000000"),
                "era este que juntava clientes diferentes na mesma empresa");
        assertFalse(DocumentoFiscal.valido("11111111111"), "CPF de digito repetido");
        assertFalse(DocumentoFiscal.valido("12345678901234"), "14 digitos quaisquer");
        assertFalse(DocumentoFiscal.valido("abc"), "texto solto");
        assertFalse(DocumentoFiscal.valido("123"), "tamanho que nao e nem CPF nem CNPJ");
    }

    @Test
    void umDIGITOtrocadoNAOpassa() {
        // O caso mais comum de todos: o lojista erra um numero ao digitar. Sem conferir o DV, a
        // fatura sai no documento errado e ninguem descobre ate ela nao nascer.
        assertFalse(DocumentoFiscal.valido("11.222.333/0001-82"), "DV2 errado");
        assertFalse(DocumentoFiscal.valido("11.222.333/0001-91"), "DV1 errado");
        assertFalse(DocumentoFiscal.valido("529.982.247-24"), "CPF com o ultimo digito trocado");
    }

    // =================================================================== o CNPJ com letra

    @Test
    void oCNPJalfanumericoDOexemploOFICIALpassa() {
        assertTrue(DocumentoFiscal.valido(CNPJ_ALFA),
                "o CNPJ alfanumerico e regra vigente desde julho/2026; recusa-lo barra cliente novo");
        assertTrue(DocumentoFiscal.ehCnpj(CNPJ_ALFA));
    }

    @Test
    void oCNPJalfanumericoCOMdvERRADOnaoPASSA() {
        // Sem isto, "aceitar alfanumerico" poderia ser so "aceitar qualquer coisa com letra".
        assertFalse(DocumentoFiscal.valido("12.ABC.345/01DE-36"));
        assertFalse(DocumentoFiscal.valido("12.ABC.345/01DF-35"), "uma letra trocada na base");
    }

    @Test
    void aLETRAnaoEHmutilada() {
        assertEquals("12ABC34501DE35", DocumentoFiscal.normalizar("12.abc.345/01de-35"),
                "o replaceAll de nao-digito deixava '1234501 35' e duas empresas diferentes podiam "
                + "cair na mesma chave");
        // E o DV nao pode ser letra: as duas ultimas posicoes continuam numericas.
        assertFalse(DocumentoFiscal.valido("12.ABC.345/01DE-3A"));
    }

    // =================================================================== a chave das empresas de hoje

    @Test
    void paraOdocumentoNUMERICOaCHAVEnaoMUDOU() {
        // ESTE TESTE GUARDA O QUE JA ESTA NO BANCO. A normalizar passou a guardar letra; se isso
        // mudasse o resultado para documento so-de-digito, as empresas que existem hoje passariam a
        // ser procuradas por outra chave -- e "mesmo CNPJ = mesma empresa" deixaria de casar.
        for (String d : new String[]{"53.953.786/0001-28", "53953786000128", "  11222333000181  ",
                                     "529.982.247-25", ""}) {
            String antigo = d.replaceAll("[^0-9]", "");
            String agora = DocumentoFiscal.normalizar(d);
            assertEquals(antigo.isBlank() ? null : antigo, agora,
                    "a chave de agrupamento mudou para o documento numerico " + d);
            assertEquals(agora, EmpresaService.normalizar(d), "EmpresaService tem de concordar");
        }
    }

    // =================================================================== no cadastro de verdade

    @Test
    void oCADASTROpublicoRECUSAeNADAehGRAVADO() {
        LojaRepository lojas = mock(LojaRepository.class);
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        EmpresaRepository empresasRepo = mock(EmpresaRepository.class);
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(empresasRepo.findByCnpj(anyString())).thenReturn(Optional.empty());

        br.com.bora.controller.SignupController controller = new br.com.bora.controller.SignupController(
                lojas, usuarios, mock(UsuarioLojaRepository.class), mock(PasswordEncoder.class),
                mock(ProvisionamentoService.class), new EmpresaService(empresasRepo, lojas),
                new FreioDeTentativas(), new br.com.bora.security.TetoDeCadastros());

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn("203.0.113.9");

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.cadastrar(new SignupRequest("Loja Teste", "00000000000000",
                        "Alguem", "alguem@teste.local", "senha-de-oito", true), http));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(),
                "documento de lixo tem de ser 400, e nao loja criada");
        // O QUE MAIS IMPORTA: a empresa nao pode ter sido criada. Recusar depois de gravar deixaria
        // uma empresa orfa com documento invalido a cada tentativa errada.
        verify(empresasRepo, never()).save(any(Empresa.class));
        verify(lojas, never()).save(any(Loja.class));
        verify(usuarios, never()).save(any(Usuario.class));
    }

    @Test
    void oCADASTROpublicoACEITAdocumentoBOMeGRAVAnormalizado() {
        LojaRepository lojas = mock(LojaRepository.class);
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        EmpresaRepository empresasRepo = mock(EmpresaRepository.class);
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(empresasRepo.findByCnpj(anyString())).thenReturn(Optional.empty());
        when(empresasRepo.save(any(Empresa.class))).thenAnswer(i -> {
            Empresa x = i.getArgument(0);
            if (x.getId() == null) x.setId(99L);
            return x;
        });
        when(lojas.save(any(Loja.class))).thenAnswer(i -> {
            Loja l = i.getArgument(0);
            if (l.id == null) l.id = 500L;
            return l;
        });
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            if (u.getId() == null) u.setId(300L);
            return u;
        });

        br.com.bora.controller.SignupController controller = new br.com.bora.controller.SignupController(
                lojas, usuarios, mock(UsuarioLojaRepository.class), mock(PasswordEncoder.class),
                mock(ProvisionamentoService.class), new EmpresaService(empresasRepo, lojas),
                new FreioDeTentativas(), new br.com.bora.security.TetoDeCadastros());

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn("203.0.113.9");

        controller.cadastrar(new SignupRequest("Loja Boa", CNPJ_BOM,
                "Alguem", "alguem@teste.local", "senha-de-oito", true), http);

        org.mockito.ArgumentCaptor<Loja> gravada = org.mockito.ArgumentCaptor.forClass(Loja.class);
        verify(lojas).save(gravada.capture());
        assertEquals("11222333000181", gravada.getValue().getDocumento(),
                "o documento tem de ser gravado normalizado, igual a chave da empresa");
    }
}
