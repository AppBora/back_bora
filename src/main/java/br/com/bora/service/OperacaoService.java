package br.com.bora.service;

import br.com.bora.entity.*;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Configurações operacionais: taxas de entrega, formas de pagamento, horário e motivos de cancelamento. */
@Service
public class OperacaoService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final TaxaEntregaRepository taxas;
    private final FormaPagamentoRepository formas;
    private final HorarioFuncionamentoRepository horarios;
    private final MotivoCancelamentoRepository motivos;
    private final AuthContext ctx;

    public OperacaoService(TaxaEntregaRepository taxas, FormaPagamentoRepository formas,
                           HorarioFuncionamentoRepository horarios, MotivoCancelamentoRepository motivos, AuthContext ctx) {
        this.taxas = taxas; this.formas = formas; this.horarios = horarios; this.motivos = motivos; this.ctx = ctx;
    }

    // ---------- Taxas de entrega ----------
    public List<TaxaEntrega> taxas() { return taxas.findByLojaIdOrderByBairroAsc(ctx.lojaId()); }
    public TaxaEntrega salvarTaxa(TaxaEntrega t) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        if (t.bairro == null || t.bairro.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bairro é obrigatório");
        TaxaEntrega alvo = t.id == null ? new TaxaEntrega()
                : taxas.findByIdAndLojaId(t.id, loja).orElseThrow(() -> naoEncontrado("Taxa"));
        alvo.lojaId = loja; alvo.bairro = t.bairro; alvo.taxa = t.taxa == null ? BigDecimal.ZERO : t.taxa;
        alvo.tempoMin = t.tempoMin; alvo.ativo = t.ativo == null || t.ativo;
        return taxas.save(alvo);
    }
    public void excluirTaxa(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        taxas.delete(taxas.findByIdAndLojaId(id, ctx.lojaId()).orElseThrow(() -> naoEncontrado("Taxa"))); }

    // ---------- Formas de pagamento ----------
    public List<FormaPagamento> formas() {
        Long loja = ctx.lojaId();
        if (formas.countByLojaId(loja) == 0) seedFormas(loja);
        return formas.findByLojaIdOrderByOrdemAscDescricaoAsc(loja);
    }
    private void seedFormas(Long loja) {
        String[][] base = {{"Dinheiro","troco"},{"Pix",""},{"Cartão de débito",""},{"Cartão de crédito",""},{"Pago no app","online"}};
        int o = 0;
        for (String[] b : base) { FormaPagamento f = new FormaPagamento(); f.lojaId = loja; f.descricao = b[0];
            f.comTroco = b[1].equals("troco"); f.online = b[1].equals("online"); f.ativo = true; f.ordem = o++; formas.save(f); }
    }
    public FormaPagamento salvarForma(FormaPagamento f) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        if (f.descricao == null || f.descricao.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descrição é obrigatória");
        FormaPagamento alvo = f.id == null ? new FormaPagamento()
                : formas.findByIdAndLojaId(f.id, loja).orElseThrow(() -> naoEncontrado("Forma de pagamento"));
        alvo.lojaId = loja; alvo.descricao = f.descricao; alvo.comTroco = bool(f.comTroco); alvo.online = bool(f.online);
        alvo.ativo = f.ativo == null || f.ativo; alvo.ordem = f.ordem == null ? 0 : f.ordem;
        return formas.save(alvo);
    }
    public void excluirForma(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        formas.delete(formas.findByIdAndLojaId(id, ctx.lojaId()).orElseThrow(() -> naoEncontrado("Forma de pagamento"))); }

    // ---------- Horário de funcionamento ----------
    public List<HorarioFuncionamento> horarios() {
        Long loja = ctx.lojaId();
        List<HorarioFuncionamento> lista = horarios.findByLojaIdOrderByDiaAsc(loja);
        if (lista.isEmpty()) {
            for (int d = 0; d < 7; d++) { HorarioFuncionamento h = new HorarioFuncionamento();
                h.lojaId = loja; h.dia = d; h.abre = "18:00"; h.fecha = "23:00"; h.aberto = true; horarios.save(h); }
            lista = horarios.findByLojaIdOrderByDiaAsc(loja);
        }
        return lista;
    }
    @Transactional
    public List<HorarioFuncionamento> salvarHorarios(List<HorarioFuncionamento> entrada) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        List<HorarioFuncionamento> atual = horarios.findByLojaIdOrderByDiaAsc(loja);
        for (HorarioFuncionamento e : entrada) {
            if (e.dia == null || e.dia < 0 || e.dia > 6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dia inválido (use 0=domingo a 6=sábado)");
            HorarioFuncionamento alvo = atual.stream().filter(h -> h.dia.equals(e.dia)).findFirst().orElseGet(HorarioFuncionamento::new);
            alvo.lojaId = loja; alvo.dia = e.dia; alvo.abre = e.abre; alvo.fecha = e.fecha; alvo.aberto = bool(e.aberto);
            horarios.save(alvo);
        }
        return horarios.findByLojaIdOrderByDiaAsc(loja);
    }
    /** Loja aberta agora (timezone BR)? */
    public boolean abertaAgora(Long loja) {
        LocalDate hoje = LocalDate.now(ZONE);
        int dia = hoje.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : hoje.getDayOfWeek().getValue();
        LocalTime agora = LocalTime.now(ZONE);
        for (HorarioFuncionamento h : horarios.findByLojaIdOrderByDiaAsc(loja)) {
            if (!h.dia.equals(dia)) continue;
            if (!Boolean.TRUE.equals(h.aberto) || h.abre == null || h.fecha == null) return false;
            try {
                LocalTime a = LocalTime.parse(h.abre), f = LocalTime.parse(h.fecha);
                return f.isAfter(a) ? (!agora.isBefore(a) && !agora.isAfter(f)) : (!agora.isBefore(a) || !agora.isAfter(f)); // vira o dia
            } catch (Exception e) { return true; }
        }
        return true; // sem config = sempre aberto
    }

    // ---------- Motivos de cancelamento ----------
    public List<MotivoCancelamento> motivos() {
        Long loja = ctx.lojaId();
        List<MotivoCancelamento> lista = motivos.findByLojaIdOrderByDescricaoAsc(loja);
        if (lista.isEmpty()) {
            for (String m : new String[]{"Cliente desistiu", "Endereço não encontrado", "Fora de área", "Produto em falta", "Pedido duplicado", "Cliente não atende"}) {
                MotivoCancelamento mc = new MotivoCancelamento(); mc.lojaId = loja; mc.descricao = m; mc.ativo = true; motivos.save(mc);
            }
            lista = motivos.findByLojaIdOrderByDescricaoAsc(loja);
        }
        return lista;
    }
    public MotivoCancelamento salvarMotivo(MotivoCancelamento m) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        if (m.descricao == null || m.descricao.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descrição é obrigatória");
        MotivoCancelamento alvo = m.id == null ? new MotivoCancelamento()
                : motivos.findByIdAndLojaId(m.id, loja).orElseThrow(() -> naoEncontrado("Motivo"));
        alvo.lojaId = loja; alvo.descricao = m.descricao; alvo.ativo = m.ativo == null || m.ativo;
        return motivos.save(alvo);
    }
    public void excluirMotivo(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        motivos.delete(motivos.findByIdAndLojaId(id, ctx.lojaId()).orElseThrow(() -> naoEncontrado("Motivo"))); }

    private boolean bool(Boolean b) { return Boolean.TRUE.equals(b); }
    private ResponseStatusException naoEncontrado(String o) { return new ResponseStatusException(HttpStatus.NOT_FOUND, o + " não encontrado"); }
}
