package br.com.bora.config;

import br.com.bora.repository.LojaRepository;
import br.com.bora.service.PromocaoService;
import br.com.bora.service.VendasSaudeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Termômetro de vendas + promoções automáticas (custo zero: só consultas + job).
 * A cada 30 min: encerra promoções vencidas e, para cada loja em queda de vendas,
 * abre uma promoção relâmpago automática (respeitando as guardas do serviço).
 */
@Slf4j
@Component
public class SaudeVendasScheduler {

    private final LojaRepository lojas;
    private final VendasSaudeService saude;
    private final PromocaoService promocoes;

    public SaudeVendasScheduler(LojaRepository lojas, VendasSaudeService saude, PromocaoService promocoes) {
        this.lojas = lojas;
        this.saude = saude;
        this.promocoes = promocoes;
    }

    @Scheduled(fixedDelayString = "${bora.saude-vendas.intervalo-ms:1800000}", initialDelay = 60000)
    public void monitorar() {
        try {
            promocoes.encerrarVencidas();
        } catch (Exception e) {
            log.warn("Falha ao encerrar promoções vencidas: {}", e.getMessage());
        }
        lojas.findAll().forEach(loja -> {
            try {
                if (loja.getId() != null && saude.emQueda(loja.getId())) {
                    promocoes.abrirAutomatica(loja.getId());
                }
            } catch (Exception e) {
                log.warn("Falha no monitor de vendas da loja {}: {}", loja.getId(), e.getMessage());
            }
        });
    }
}
