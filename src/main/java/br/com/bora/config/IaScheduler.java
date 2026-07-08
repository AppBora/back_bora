package br.com.bora.config;

import br.com.bora.service.IaService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rotina diária do Módulo IA: Gerente Virtual + Recuperador, 8h da manhã (Brasília). */
@Component
public class IaScheduler {

    private final IaService ia;

    public IaScheduler(IaService ia) {
        this.ia = ia;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "America/Sao_Paulo")
    public void rotinaDiaria() {
        ia.rotinaDiaria();
    }
}
