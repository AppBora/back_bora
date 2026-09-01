package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rede multi-loja: lojas vinculadas ao usuário, troca de contexto e balancete consolidado. */
@Service
public class RedeService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final UsuarioLojaRepository vinculos;
    private final UsuarioRepository usuarios;
    private final LojaRepository lojas;
    private final PedidoRepository pedidos;
    private final JwtService jwt;
    private final AuthContext ctx;
    private final EmpresaService empresas;

    public RedeService(UsuarioLojaRepository vinculos, UsuarioRepository usuarios, LojaRepository lojas,
                       PedidoRepository pedidos, JwtService jwt, AuthContext ctx, EmpresaService empresas) {
        this.empresas = empresas;
        this.vinculos = vinculos;
        this.usuarios = usuarios;
        this.lojas = lojas;
        this.pedidos = pedidos;
        this.jwt = jwt;
        this.ctx = ctx;
    }

    /** Lojas vinculadas ao usuário logado (a atual vem marcada). */
    public List<Map<String, Object>> minhasLojas() {
        Long userId = ctx.atual().userId();
        Long lojaAtual = ctx.lojaId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UsuarioLoja v : vinculos.findByUsuarioId(userId)) {
            Loja l = lojas.findById(v.getLojaId()).orElse(null);
            if (l == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("nome", l.getNome());
            m.put("ativo", l.getAtivo());
            m.put("atual", l.getId().equals(lojaAtual));
            out.add(m);
        }
        return out;
    }

    /** Troca o contexto para outra loja vinculada e devolve um novo token. */
    public Map<String, Object> trocarLoja(Long lojaId) {
        Long userId = ctx.atual().userId();
        if (lojaId == null || !vinculos.existsByUsuarioIdAndLojaId(userId, lojaId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem vínculo com essa loja");
        }
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        // Trocar de loja é emissão de token novo: não pode ser a porta dos fundos para entrar
        // numa loja que a plataforma suspendeu ou arquivou.
        if (loja.bloqueadaPelaPlataforma()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Loja desativada pela plataforma");
        }
        Usuario u = usuarios.findById(userId)
                .filter(Usuario::getAtivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário inválido"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", jwt.gerar(u, lojaId));
        m.put("nome", u.getNome());
        m.put("papel", u.getPapel().name());
        m.put("lojaId", lojaId);
        m.put("lojaNome", loja.getNome());
        return m;
    }

    /**
     * Balancete da rede: faturamento/pedidos por loja vinculada + total consolidado,
     * no período [inicio, fim] (datas locais America/Sao_Paulo, fim inclusivo).
     */
    public Map<String, Object> balancete(LocalDate inicio, LocalDate fim) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        if (inicio == null) inicio = LocalDate.now(ZONE).withDayOfMonth(1);
        if (fim == null) fim = LocalDate.now(ZONE);
        if (fim.isBefore(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data final anterior à inicial");
        }
        OffsetDateTime ini = inicio.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimExc = fim.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<Map<String, Object>> porLoja = new ArrayList<>();
        BigDecimal fatTotal = BigDecimal.ZERO;
        long pedTotal = 0, cancTotal = 0;

        for (UsuarioLoja v : vinculos.findByUsuarioId(ctx.atual().userId())) {
            Loja l = lojas.findById(v.getLojaId()).orElse(null);
            if (l == null) continue;
            BigDecimal fat = pedidos.somaReceita(l.getId(), ini, fimExc);
            long ped = pedidos.contaPedidosValidos(l.getId(), ini, fimExc);
            long canc = pedidos.contaPedidosCancelados(l.getId(), ini, fimExc);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lojaId", l.getId());
            m.put("loja", l.getNome());
            m.put("ativa", l.getAtivo());
            m.put("faturamento", fat);
            m.put("pedidos", ped);
            m.put("cancelados", canc);
            m.put("ticketMedio", ped > 0 ? fat.divide(BigDecimal.valueOf(ped), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            porLoja.add(m);
            fatTotal = fatTotal.add(fat);
            pedTotal += ped;
            cancTotal += canc;
        }

        // Representatividade: quanto cada loja pesa no faturamento da rede (%)
        for (Map<String, Object> m : porLoja) {
            BigDecimal fat = (BigDecimal) m.get("faturamento");
            BigDecimal rep = fatTotal.signum() == 0 ? BigDecimal.ZERO
                    : fat.multiply(BigDecimal.valueOf(100)).divide(fatTotal, 2, RoundingMode.HALF_UP);
            m.put("representatividade", rep);
        }

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("faturamento", fatTotal);
        total.put("pedidos", pedTotal);
        total.put("cancelados", cancTotal);
        total.put("ticketMedio", pedTotal > 0 ? fatTotal.divide(BigDecimal.valueOf(pedTotal), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        total.put("representatividade", new BigDecimal("100.00"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inicio", inicio.toString());
        out.put("fim", fim.toString());
        out.put("lojas", porLoja);
        out.put("total", total);
        return out;
    }

    // ---- Equipe da rede: um usuário (ex.: gerente) atendendo mais de uma loja da mesma empresa ----

    /** Usuários da loja atual e as lojas de cada um — base da tela de equipe da rede. */
    public List<Map<String, Object>> equipe() {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        Long lojaAtual = ctx.lojaId();
        List<Map<String, Object>> saida = new ArrayList<>();
        for (UsuarioLoja v : vinculos.findByLojaId(lojaAtual)) {
            usuarios.findById(v.getUsuarioId()).ifPresent(u -> {
                List<Map<String, Object>> dele = new ArrayList<>();
                for (UsuarioLoja x : vinculos.findByUsuarioId(u.getId())) {
                    lojas.findById(x.getLojaId()).ifPresent(l -> dele.add(Map.of(
                            "id", l.id, "nome", l.nome == null ? "Loja" : l.nome,
                            "principal", l.id.equals(u.getLojaId()))));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("nome", u.getNome());
                m.put("email", u.getEmail());
                m.put("papel", u.getPapel().name());
                m.put("ativo", u.getAtivo());
                m.put("lojas", dele);
                saida.add(m);
            });
        }
        return saida;
    }

    /** Lojas da mesma empresa que a atual — as únicas às quais se pode vincular alguém. */
    public List<Map<String, Object>> lojasDaEmpresa() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long atual = ctx.lojaId();
        List<Map<String, Object>> saida = new ArrayList<>();
        for (Loja l : lojas.findAll()) {
            if (l.arquivada() || !empresas.mesmaEmpresa(atual, l.id)) continue;
            saida.add(Map.of("id", l.id, "nome", l.nome == null ? "Loja" : l.nome,
                    "atual", l.id.equals(atual)));
        }
        return saida;
    }

    /**
     * Dá a um usuário acesso a outra loja DA MESMA EMPRESA. É o ponto onde o isolamento pode furar:
     * sem a checagem de empresa, um administrador de loja vincularia gente sua à loja de outro
     * cliente só sabendo o id. Por isso a empresa é a fonte de verdade, não o vínculo.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> vincular(Long usuarioId, Long lojaId) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Usuario u = usuarioDaMinhaEmpresa(usuarioId);
        Loja alvo = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (!empresas.mesmaEmpresa(ctx.lojaId(), lojaId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Essa loja é de outra empresa — só dá para vincular às lojas da sua rede");
        }
        if (alvo.bloqueadaPelaPlataforma()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Loja desativada pela plataforma");
        }
        if (vinculos.existsByUsuarioIdAndLojaId(usuarioId, lojaId)) {
            return Map.of("usuarioId", usuarioId, "lojaId", lojaId, "criado", false);
        }
        UsuarioLoja v = new UsuarioLoja();
        v.setUsuarioId(usuarioId);
        v.setLojaId(lojaId);
        vinculos.save(v);
        return Map.of("usuarioId", usuarioId, "lojaId", lojaId, "criado", true,
                "usuario", u.getNome(), "loja", alvo.nome == null ? "" : alvo.nome);
    }

    /** Tira o acesso do usuário a uma loja. O efeito é imediato: o filtro revalida o vínculo. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> desvincular(Long usuarioId, Long lojaId) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Usuario u = usuarioDaMinhaEmpresa(usuarioId);
        if (!empresas.mesmaEmpresa(ctx.lojaId(), lojaId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Loja de outra empresa");
        }
        if (lojaId.equals(u.getLojaId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta é a loja principal do usuário — ele ficaria sem casa. Troque a principal antes.");
        }
        if (vinculos.countByUsuarioId(usuarioId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "É o último acesso do usuário");
        }
        vinculos.deleteByUsuarioIdAndLojaId(usuarioId, lojaId);
        return Map.of("usuarioId", usuarioId, "lojaId", lojaId, "removido", true);
    }

    /** O usuário alvo tem que ser da minha empresa — senão eu estaria mexendo em gente de outro cliente. */
    private Usuario usuarioDaMinhaEmpresa(Long usuarioId) {
        Usuario u = usuarios.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        if (u.getPapel() == br.com.bora.entity.Papel.ADMINISTRADOR_BORA) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrador da plataforma não tem loja");
        }
        if (u.getLojaId() == null || !empresas.mesmaEmpresa(ctx.lojaId(), u.getLojaId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }
        return u;
    }
}
