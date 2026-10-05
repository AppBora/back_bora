package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quem aceitou os Termos, em que versao, e quem ainda precisa aceitar.
 *
 * <h2>Por que existe</h2>
 * <p>O aceite nasceu junto com a tela de cadastro, entao so quem se cadastrou pelo site tem registro.
 * As lojas que ja existiam — e as que a plataforma cria pelo painel administrativo, que nao pede
 * aceite — ficaram com {@code termos_versao} nulo: nenhum contrato aceito com ninguem, num texto que
 * fala de pagamento, suspensao, cancelamento e reembolso. Com o corte por falta de pagamento ligado
 * (04/10/2026), suspender quem nunca aceitou nada ficou pior ainda.</p>
 *
 * <h2>O que este servico NAO faz</h2>
 * <p>Nao preenche aceite de ninguem. A tentacao obvia era rodar um UPDATE marcando as cinco lojas
 * como tendo aceitado hoje — isso nao e consertar o registro, e inventar um consentimento que nunca
 * houve, e um aceite fabricado vale menos que aceite nenhum se alguem questionar. O que existe aqui e
 * o caminho para o lojista aceitar de verdade, gravando a data real.</p>
 *
 * <h2>Quem pode aceitar</h2>
 * <p>So o ADMINISTRADOR_LOJA, que e quem responde pela empresa. GERENTE nao obriga a loja a um
 * contrato. E o suporte da plataforma <b>tambem nao pode</b>: o acesso de suporte entra na loja
 * mantendo o papel ADMINISTRADOR_BORA, e deixar o suporte clicar em "aceito" pelo cliente seria a
 * mesma fabricacao, so feita a mao.</p>
 *
 * <p>Vale tambem para o futuro: quando o texto dos Termos mudar de versao, quem estiver numa versao
 * antiga volta a aparecer como pendente e aceita de novo.</p>
 */
@Slf4j
@Service
public class TermosService {

    /**
     * Versao do texto publicado em borahapp.com.br/termos.html, no formato da data de "Ultima
     * atualizacao" da propria pagina.
     *
     * <p>Mudou o texto? Mude esta constante junto com a data da pagina. O
     * {@code VersaoDosTermosTest} compara as duas e quebra o build se alguem esquecer a metade — sem
     * isso o comprovante de aceite aponta para um texto que nao existe mais.</p>
     */
    public static final String VERSAO_VIGENTE = "2026-10-04";

    private final LojaRepository lojas;

    public TermosService(LojaRepository lojas) {
        this.lojas = lojas;
    }

    /** Situacao do aceite desta loja, para a tela decidir se pede o aceite. */
    public Map<String, Object> situacao(Long lojaId, String papel) {
        Loja loja = exigirLoja(lojaId);
        boolean pendente = !VERSAO_VIGENTE.equals(loja.termosVersao);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pendente", pendente);
        m.put("versaoVigente", VERSAO_VIGENTE);
        m.put("versaoAceita", loja.termosVersao);
        m.put("aceitoEm", loja.termosAceitosEm);
        m.put("primeiroAceite", loja.termosVersao == null);
        m.put("podeAceitar", "ADMINISTRADOR_LOJA".equals(papel));
        if (pendente && !"ADMINISTRADOR_LOJA".equals(papel)) {
            m.put("recado", "ADMINISTRADOR_BORA".equals(papel)
                    ? "O aceite é do lojista. O suporte não aceita pelo cliente."
                    : "Peça ao administrador da loja para aceitar os Termos.");
        }
        return m;
    }

    /** Registra o aceite desta loja, com a data real e a origem da chamada. */
    @Transactional
    public Map<String, Object> aceitar(Long lojaId, String papel, String origem) {
        if ("ADMINISTRADOR_BORA".equals(papel)) {
            // Deixar o suporte aceitar pelo cliente e fabricar consentimento a mao.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "O aceite dos Termos é do lojista. O suporte da plataforma não pode aceitar pelo cliente.");
        }
        if (!"ADMINISTRADOR_LOJA".equals(papel)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só o administrador da loja pode aceitar os Termos em nome da empresa.");
        }
        Loja loja = exigirLoja(lojaId);
        if (VERSAO_VIGENTE.equals(loja.termosVersao)) {
            return situacao(lojaId, papel); // ja aceitou esta versao; nao reescreve a data original
        }
        String anterior = loja.termosVersao;
        loja.termosAceitosEm = OffsetDateTime.now();
        loja.termosVersao = VERSAO_VIGENTE;
        loja.termosAceitosDe = origem;
        lojas.save(loja);
        log.info("AUDITORIA termos: loja {} aceitou a versao {} (antes: {}) de {}",
                lojaId, VERSAO_VIGENTE, anterior == null ? "nenhuma" : anterior, origem);
        return situacao(lojaId, papel);
    }

    private Loja exigirLoja(Long lojaId) {
        return lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
    }
}
