package br.com.bora.service;

import br.com.bora.entity.Promocao;
import br.com.bora.repository.PromocaoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
public class PromocaoService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final PromocaoRepository repo;

    public PromocaoService(PromocaoRepository repo) {
        this.repo = repo;
    }

    public List<Promocao> listar(Long lojaId) {
        return repo.findByLojaIdOrderByInicioDesc(lojaId);
    }

    /**
     * O que vale como codigo de promocao: letras, numeros, ponto, hifen e sublinhado.
     *
     * <h2>Por que isto existe (achado pelo QA em 26/10/2026)</h2>
     * <p>O {@code codigo} era texto livre, gravado sem nenhuma validacao. A tela de Promocoes o
     * desenha dentro de uma string JS: {@code onclick="__encerrar(id, '...')"}. Um codigo como
     * {@code X'+alert(1)+'} — 13 caracteres numa coluna de 40 — escapava da string e rodava na
     * sessao de quem abrisse a tela.</p>
     *
     * <p>Quem grava e um GERENTE. As vitimas naturais sao o ADMINISTRADOR_LOJA e o <b>suporte da
     * plataforma</b> operando dentro da loja por "Entrar na loja" — e nesse caso o atacante leva um
     * token de ADMINISTRADOR_BORA.</p>
     *
     * <p>O {@code esc} da tela foi corrigido no mesmo commit (nao escapava apostrofe). As duas
     * pontas, de proposito: escapar na tela protege o render de hoje, e recusar aqui protege o
     * proximo lugar que for mostrar esse valor — relatorio, WhatsApp, nota, qualquer um.</p>
     *
     * <p>Codigo de cupom e de promocao e digitado por cliente ao telefone e lido em voz alta. O
     * conjunto permitido e o que serve para isso; nao e restricao arbitraria.</p>
     */
    private static final java.util.regex.Pattern CODIGO_VALIDO =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{1,40}$");

    public Promocao criarManual(Long lojaId, Promocao dados) {
        if (dados.getDescricao() == null || dados.getDescricao().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descrição da promoção é obrigatória");
        }
        String codigo = dados.getCodigo();
        if (codigo != null && !codigo.isBlank()) {
            codigo = codigo.trim();
            if (!CODIGO_VALIDO.matcher(codigo).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Código de promoção inválido. Use letras, números, ponto, hífen ou "
                                + "sublinhado, até 40 caracteres — é um código que o cliente digita "
                                + "e lê em voz alta.");
            }
            dados.setCodigo(codigo);
        }
        // Guarda: não permite duas promoções ativas ao mesmo tempo na loja.
        if (repo.existsByLojaIdAndAtivaTrue(lojaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe uma promoção ativa. Encerre-a antes de abrir outra.");
        }
        dados.setId(null);
        dados.setLojaId(lojaId);
        dados.setOrigem("MANUAL");
        dados.setAtiva(true);
        if (dados.getInicio() == null) dados.setInicio(OffsetDateTime.now(ZONE));
        if (dados.getUsos() == null) dados.setUsos(0);
        return repo.save(dados);
    }

    /**
     * Abre uma promoção relâmpago automática quando há queda de vendas.
     * Guardas: não abre se já há promo ativa nem se já houve uma AUTO hoje.
     * Retorna a promoção criada, ou null se uma guarda bloqueou.
     */
    public Promocao abrirAutomatica(Long lojaId) {
        if (repo.existsByLojaIdAndAtivaTrue(lojaId)) return null;

        OffsetDateTime inicioHoje = OffsetDateTime.now(ZONE)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        if (repo.countByLojaIdAndOrigemAndInicioAfter(lojaId, "AUTO", inicioHoje) >= 1) return null;

        OffsetDateTime agora = OffsetDateTime.now(ZONE);
        Promocao p = new Promocao();
        p.setLojaId(lojaId);
        p.setTipo("RELAMPAGO");
        p.setDescricao("Cupom relâmpago automático — 15% de desconto por 3 horas");
        p.setCodigo("BORA15L" + lojaId); // código único por loja
        p.setPercentualDesconto(new BigDecimal("15.00"));
        p.setInicio(agora);
        p.setFim(agora.plusHours(3));
        p.setLimiteUsos(20);
        p.setUsos(0);
        p.setAtiva(true);
        p.setOrigem("AUTO");
        Promocao salva = repo.save(p);
        log.warn("Promoção automática aberta (queda de vendas) — loja {} código {}", lojaId, salva.getCodigo());
        return salva;
    }

    /** Encerra manualmente uma promoção da loja (libera a loja para criar outra). */
    public Promocao encerrar(Long lojaId, Long promoId) {
        Promocao p = repo.findByIdAndLojaId(promoId, lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promoção não encontrada"));
        p.setAtiva(false);
        return repo.save(p);
    }

    /** Encerra promoções vencidas (fim < agora). */
    @org.springframework.transaction.annotation.Transactional
    public int encerrarVencidas() {
        List<Promocao> vencidas = repo.findByAtivaTrueAndFimBefore(OffsetDateTime.now());
        vencidas.forEach(p -> p.setAtiva(false));
        repo.saveAll(vencidas);
        return vencidas.size();
    }
}
