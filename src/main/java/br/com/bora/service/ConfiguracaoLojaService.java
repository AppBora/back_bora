package br.com.bora.service;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Plano;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

@Service
public class ConfiguracaoLojaService {

    private final ConfiguracaoLojaRepository repo;
    private final PlanoService planos;
    private final AuthContext ctx;

    public ConfiguracaoLojaService(ConfiguracaoLojaRepository repo, PlanoService planos, AuthContext ctx) {
        this.repo = repo;
        this.planos = planos;
        this.ctx = ctx;
    }

    public ConfiguracaoLoja atual() {
        Long lojaId = ctx.lojaId();
        return repo.findByLojaId(lojaId).orElseGet(() -> {
            ConfiguracaoLoja c = new ConfiguracaoLoja();
            c.lojaId = lojaId;
            return c;
        });
    }

    /** RN10 — só aplica os campos de white-label que o plano da loja libera. */
    public ConfiguracaoLoja atualizar(ConfiguracaoLoja dados) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        Plano plano = planos.plano(lojaId);

        ConfiguracaoLoja c = repo.findByLojaId(lojaId).orElseGet(() -> {
            ConfiguracaoLoja novo = new ConfiguracaoLoja();
            novo.lojaId = lojaId;
            return novo;
        });

        // Disponível em todos os planos — só sobrescreve o que veio preenchido (não apaga ao omitir)
        if (dados.nomeExibicao != null) c.nomeExibicao = dados.nomeExibicao;
        if (dados.logoUrl != null)      c.logoUrl = dados.logoUrl;
        if (dados.corPrimaria != null)  c.corPrimaria = dados.corPrimaria;
        if (dados.nomeSistema != null)  c.nomeSistema = dados.nomeSistema;

        // Cashback: 0 desliga. Teto de 50% para um erro de digitação não virar prejuízo.
        if (dados.cashbackPercentual != null) {
            if (dados.cashbackPercentual.signum() < 0
                    || dados.cashbackPercentual.compareTo(new java.math.BigDecimal("50")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "O cashback deve ficar entre 0% e 50%");
            }
            c.cashbackPercentual = dados.cashbackPercentual;
        }

        // Liberados conforme o plano
        if (plano.permiteCoresSecundarias() && dados.corSecundaria != null) c.corSecundaria = dados.corSecundaria;
        if (plano.permiteBanner() && dados.bannerUrl != null)               c.bannerUrl = dados.bannerUrl;
        if (plano.permiteSubdominio() && dados.subdominio != null)          c.subdominio = dados.subdominio;

        // Marca Bora só pode ser removida no Enterprise (futuro)
        c.mostrarMarcaBora = !plano.permiteRemoverMarca();

        return repo.save(c);
    }
}
