package br.com.bora.service;

import br.com.bora.repository.ConfigPlataformaRepository;
import org.springframework.stereotype.Service;

/**
 * Módulo fiscal (NFC-e via API externa, ex.: Focus NFe).
 * DESLIGADO por padrão (decisão 2026-07-07): só será habilitado pelo ADMINISTRADOR_BORA
 * quando o faturamento da plataforma atingir R$ 5 mil/mês livre.
 * Toda emissão futura DEVE passar por {@link #habilitado()} antes de qualquer chamada externa.
 */
@Service
public class FiscalService {

    private final ConfigPlataformaRepository configs;

    public FiscalService(ConfigPlataformaRepository configs) {
        this.configs = configs;
    }

    /** true somente se o ADMINISTRADOR_BORA ligou o módulo fiscal na plataforma. */
    public boolean habilitado() {
        return configs.findById("fiscal.habilitado")
                .map(c -> Boolean.parseBoolean(c.getValor()))
                .orElse(false);
    }
}
