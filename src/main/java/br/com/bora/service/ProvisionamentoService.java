package br.com.bora.service;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.FormaPagamento;
import br.com.bora.entity.HorarioFuncionamento;
import br.com.bora.entity.MotivoCancelamento;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.repository.FormaPagamentoRepository;
import br.com.bora.repository.HorarioFuncionamentoRepository;
import br.com.bora.repository.MotivoCancelamentoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Provisiona os defaults de uma loja recém-criada para ela já nascer operável:
 * configuração visual base, formas de pagamento comuns, motivos de cancelamento e
 * horário de funcionamento. Idempotente — só cria o que ainda não existe, então pode
 * ser chamado no cadastro e também rodar em lojas antigas sem duplicar nada.
 */
@Slf4j
@Service
public class ProvisionamentoService {

    private static final String COR_PADRAO = "#7c3aed";

    private final ConfiguracaoLojaRepository configs;
    private final FormaPagamentoRepository formas;
    private final MotivoCancelamentoRepository motivos;
    private final HorarioFuncionamentoRepository horarios;

    public ProvisionamentoService(ConfiguracaoLojaRepository configs, FormaPagamentoRepository formas,
                                  MotivoCancelamentoRepository motivos, HorarioFuncionamentoRepository horarios) {
        this.configs = configs;
        this.formas = formas;
        this.motivos = motivos;
        this.horarios = horarios;
    }

    /** Semeia os defaults da loja. Seguro para chamar mais de uma vez. */
    @Transactional
    public void semear(Long lojaId, String nomeLoja) {
        if (lojaId == null) return;
        semearConfig(lojaId, nomeLoja);
        semearFormas(lojaId);
        semearMotivos(lojaId);
        semearHorarios(lojaId);
        log.info("Provisionamento: defaults garantidos para a loja {}", lojaId);
    }

    private void semearConfig(Long lojaId, String nomeLoja) {
        if (configs.findByLojaId(lojaId).isPresent()) return;
        ConfiguracaoLoja c = new ConfiguracaoLoja();
        c.lojaId = lojaId;
        c.nomeExibicao = nomeLoja == null || nomeLoja.isBlank() ? "Minha Loja" : nomeLoja.trim();
        c.corPrimaria = COR_PADRAO;
        c.mostrarMarcaBora = true;
        configs.save(c);
    }

    private void semearFormas(Long lojaId) {
        if (formas.countByLojaId(lojaId) > 0) return;
        formas.save(forma(lojaId, "Dinheiro", true, false, 0));
        formas.save(forma(lojaId, "PIX", false, true, 1));
        formas.save(forma(lojaId, "Cartão de crédito", false, false, 2));
        formas.save(forma(lojaId, "Cartão de débito", false, false, 3));
    }

    private FormaPagamento forma(Long lojaId, String descricao, boolean comTroco, boolean online, int ordem) {
        FormaPagamento f = new FormaPagamento();
        f.lojaId = lojaId;
        f.descricao = descricao;
        f.comTroco = comTroco;
        f.online = online;
        f.ativo = true;
        f.ordem = ordem;
        return f;
    }

    private void semearMotivos(Long lojaId) {
        if (!motivos.findByLojaIdOrderByDescricaoAsc(lojaId).isEmpty()) return;
        for (String desc : List.of("Cliente desistiu", "Fora da área de entrega",
                "Produto em falta", "Endereço não encontrado", "Loja fechada")) {
            MotivoCancelamento m = new MotivoCancelamento();
            m.lojaId = lojaId;
            m.descricao = desc;
            m.ativo = true;
            motivos.save(m);
        }
    }

    private void semearHorarios(Long lojaId) {
        if (!horarios.findByLojaIdOrderByDiaAsc(lojaId).isEmpty()) return;
        for (int dia = 0; dia <= 6; dia++) {
            HorarioFuncionamento h = new HorarioFuncionamento();
            h.lojaId = lojaId;
            h.dia = dia;
            h.abre = "18:00";
            h.fecha = "23:00";
            h.aberto = true;
            horarios.save(h);
        }
    }
}
