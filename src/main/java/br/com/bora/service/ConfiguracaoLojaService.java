package br.com.bora.service;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Plano;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.security.AuthContext;
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

        // Disponível em todos os planos
        c.nomeExibicao = dados.nomeExibicao;
        c.logoUrl = dados.logoUrl;
        c.corPrimaria = dados.corPrimaria;
        c.nomeSistema = dados.nomeSistema;

        // Liberados conforme o plano
        if (plano.permiteCoresSecundarias()) c.corSecundaria = dados.corSecundaria;
        if (plano.permiteBanner())           c.bannerUrl = dados.bannerUrl;
        if (plano.permiteSubdominio())       c.subdominio = dados.subdominio;

        // Marca Bora só pode ser removida no Enterprise (futuro)
        c.mostrarMarcaBora = !plano.permiteRemoverMarca();

        return repo.save(c);
    }
}
