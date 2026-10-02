package br.com.bora.service;

import br.com.bora.entity.HorarioFuncionamento;
import br.com.bora.repository.HorarioFuncionamentoRepository;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O horário de funcionamento passou a decidir se o cardápio aceita pedido. Antes ele não era
 * consultado em lugar nenhum, então errar aqui agora tem consequência direta: fechar uma loja que
 * está aberta é perder venda, e abrir uma que está fechada é pedido entrando sem ninguém na cozinha.
 *
 * <p>Os horários são montados em volta do relógio de agora (no fuso de São Paulo, que é o do código),
 * para o teste valer a qualquer hora do dia em que ele rodar.</p>
 */
class HorarioDeFuncionamentoTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private OperacaoService comHorarios(List<HorarioFuncionamento> horarios) {
        HorarioFuncionamentoRepository repo = mock(HorarioFuncionamentoRepository.class);
        when(repo.findByLojaIdOrderByDiaAsc(18L)).thenReturn(horarios);
        return new OperacaoService(mock(br.com.bora.repository.TaxaEntregaRepository.class),
                mock(br.com.bora.repository.FormaPagamentoRepository.class),
                repo,
                mock(br.com.bora.repository.MotivoCancelamentoRepository.class),
                mock(br.com.bora.security.AuthContext.class));
    }

    private HorarioFuncionamento dia(int dia, boolean aberto, String abre, String fecha) {
        HorarioFuncionamento h = new HorarioFuncionamento();
        h.lojaId = 18L;
        h.dia = dia;
        h.aberto = aberto;
        h.abre = abre;
        h.fecha = fecha;
        return h;
    }

    /** O código guarda domingo como 0 e o resto como 1..6 (segunda a sábado). */
    private int diaDeHoje() {
        DayOfWeek d = LocalDate.now(SP).getDayOfWeek();
        return d == DayOfWeek.SUNDAY ? 0 : d.getValue();
    }

    private String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    @Test
    void dentroDaFaixaDeHoje_estaAberta() {
        LocalTime agora = LocalTime.now(SP);
        var servico = comHorarios(List.of(dia(diaDeHoje(), true,
                hhmm(agora.minusHours(2)), hhmm(agora.plusHours(2)))));

        assertTrue(servico.abertaAgora(18L));
    }

    @Test
    void foraDaFaixaDeHoje_estaFechada() {
        LocalTime agora = LocalTime.now(SP);
        // Faixa curta, logo depois de agora: a loja ainda vai abrir.
        var servico = comHorarios(List.of(dia(diaDeHoje(), true,
                hhmm(agora.plusMinutes(30)), hhmm(agora.plusHours(3)))));

        assertFalse(servico.abertaAgora(18L), "pedido antes de abrir nao pode entrar");
    }

    @Test
    void diaMarcadoComoFechado_naoAbrePorHorarioNenhum() {
        LocalTime agora = LocalTime.now(SP);
        var servico = comHorarios(List.of(dia(diaDeHoje(), false,
                hhmm(agora.minusHours(2)), hhmm(agora.plusHours(2)))));

        assertFalse(servico.abertaAgora(18L), "folga semanal vale mesmo dentro da faixa");
    }

    @Test
    void semHorarioCadastrado_continuaAceitandoPedido() {
        assertTrue(comHorarios(List.of()).abertaAgora(18L),
                "loja que nunca configurou horario nao pode parar de vender por causa disto");
    }

    @Test
    void horarioDeOutroDiaNaoInterfere() {
        LocalTime agora = LocalTime.now(SP);
        int outroDia = (diaDeHoje() + 3) % 7;
        var servico = comHorarios(List.of(
                dia(outroDia, true, hhmm(agora.minusHours(2)), hhmm(agora.plusHours(2)))));

        assertTrue(servico.abertaAgora(18L),
                "sem linha para hoje, vale a regra de 'sem configuracao': aberta");
    }

    @Test
    void faixaQueViraADia_contaAMadrugadaComoAberta() {
        LocalTime agora = LocalTime.now(SP);
        // Abre 1h atras e fecha 1h atras "do outro lado": cobre a loja que varia a madrugada.
        var servico = comHorarios(List.of(dia(diaDeHoje(), true,
                hhmm(agora.minusHours(1)), hhmm(agora.minusHours(2)))));

        assertTrue(servico.abertaAgora(18L), "quem fecha depois da meia-noite segue aberto agora");
    }
}
