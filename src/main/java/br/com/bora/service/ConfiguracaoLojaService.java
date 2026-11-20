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
    private final br.com.bora.repository.LojaRepository lojas;

    public ConfiguracaoLojaService(ConfiguracaoLojaRepository repo, PlanoService planos, AuthContext ctx,
                                   br.com.bora.repository.LojaRepository lojas) {
        this.repo = repo;
        this.planos = planos;
        this.ctx = ctx;
        this.lojas = lojas;
    }

    public ConfiguracaoLoja atual() {
        Long lojaId = ctx.lojaId();
        ConfiguracaoLoja c = repo.findByLojaId(lojaId).orElseGet(() -> {
            ConfiguracaoLoja nova = new ConfiguracaoLoja();
            nova.lojaId = lojaId;
            return nova;
        });
        return comNomeDaLoja(c, lojaId);
    }

    /**
     * Carimba o nome real da loja na resposta. Ver {@code ConfiguracaoLoja.nomeDaLoja}.
     *
     * <p>E o que faz a rede de seguranca do painel funcionar quando o lojista apaga o "nome de
     * exibicao": a tela tem para onde cair sem pedir nada a mais ao servidor.</p>
     */
    private ConfiguracaoLoja comNomeDaLoja(ConfiguracaoLoja c, Long lojaId) {
        if (lojaId != null) c.nomeDaLoja = lojas.findById(lojaId).map(l -> l.getNome()).orElse(null);
        return c;
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
        // Validados na GRAVACAO: ver EnderecoDeImagem (a outra metade do conserto do XSS).
        if (dados.logoUrl != null)      c.logoUrl = EnderecoDeImagem.exigirValido(dados.logoUrl, "o logo da loja");
        if (dados.corPrimaria != null)  c.corPrimaria = dados.corPrimaria;
        if (dados.nomeSistema != null)  c.nomeSistema = dados.nomeSistema;
        // Custos que entram no cálculo de lucro. Zero é valor legítimo (isento / sem custo fixo),
        // por isso só ignoramos null.
        if (dados.aliquotaImposto != null) {
            if (dados.aliquotaImposto.signum() < 0 || dados.aliquotaImposto.compareTo(new java.math.BigDecimal("100")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alíquota de imposto deve ficar entre 0 e 100%");
            }
            c.aliquotaImposto = dados.aliquotaImposto;
        }
        if (dados.custoFixoMensal != null) {
            if (dados.custoFixoMensal.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custo fixo não pode ser negativo");
            }
            c.custoFixoMensal = dados.custoFixoMensal;
        }

        // Tempo de preparo e de entrega (item 5 da secao 4). Nulo = nao mexe; e nulo no banco quer
        // dizer "usa o padrao de hoje", entao quem nunca abriu esta tela continua como estava.
        //
        // AS FAIXAS, e por que elas existem: o campo alimenta o alerta de atraso da tela da cozinha.
        // Zero no preparo marcaria todo pedido como atrasado no instante em que ele entra -- o
        // mesmo defeito, ao contrario. E 240 minutos (4 h) ja e mais do que qualquer cozinha de
        // delivery leva: acima disso o que houve foi erro de digitacao, e aceitar calado faz o
        // alerta nunca mais acender.
        if (dados.tempoPreparoMin != null) {
            if (dados.tempoPreparoMin < 0 || dados.tempoPreparoMin > 240) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "O tempo de preparo deve ficar entre 1 e 240 minutos (ou 0 para automatico).");
            }
            // ZERO VOLTA AO AUTOMATICO, e este e o unico jeito de desfazer.
            //
            // Este PUT ignora campo nulo de proposito ("so sobrescreve o que veio preenchido"), e a
            // tela manda null para campo vazio -- ou seja, limpar o campo na tela NAO limparia o
            // banco, e o lojista que preenchesse 45 uma vez ficaria preso nos 45 para sempre. Com o
            // zero virando NULL aqui, "0 = automatico" na tela e verdade de ponta a ponta.
            //
            // Zero tambem nao poderia ser gravado como zero: SLA de zero minuto marca todo pedido
            // como atrasado no instante em que ele entra -- o mesmo defeito do item 5, invertido.
            c.tempoPreparoMin = dados.tempoPreparoMin == 0 ? null : dados.tempoPreparoMin;
        }
        // Entrega aceita ZERO: a loja que so vende no balcao, ou que usa so marketplace com entrega
        // deles, nao tem rua nenhuma para contar.
        if (dados.tempoEntregaMin != null) {
            if (dados.tempoEntregaMin < 0 || dados.tempoEntregaMin > 180) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "O tempo de entrega deve ficar entre 0 e 180 minutos.");
            }
            c.tempoEntregaMin = dados.tempoEntregaMin;
        }

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
        if (plano.permiteBanner() && dados.bannerUrl != null)               c.bannerUrl = EnderecoDeImagem.exigirValido(dados.bannerUrl, "o banner da loja");
        if (plano.permiteSubdominio() && dados.subdominio != null)          c.subdominio = dados.subdominio;

        // Marca Bora só pode ser removida no Enterprise (futuro)
        c.mostrarMarcaBora = !plano.permiteRemoverMarca();

        // O PUT devolve o mesmo formato do GET, nome da loja incluido: a tela usa a resposta do
        // salvar para se redesenhar, e sem isto o nome sumiria logo depois de salvar.
        return comNomeDaLoja(repo.save(c), lojaId);
    }
}
