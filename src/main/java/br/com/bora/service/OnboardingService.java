package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prontidão de operação da loja — deriva o progresso do onboarding a partir dos dados
 * reais (sem tabela nova). Alimenta o assistente "Comece por aqui" no painel.
 */
@Service
public class OnboardingService {

    private final ConfiguracaoLojaRepository configs;
    private final ProdutoRepository produtos;
    private final TaxaEntregaRepository taxas;
    private final HorarioFuncionamentoRepository horarios;
    private final UsuarioRepository usuarios;
    private final AssinaturaRepository assinaturas;
    private final IntegracaoCanalRepository integracoes;
    private final LojaRepository lojas;
    private final AuthContext ctx;

    public OnboardingService(ConfiguracaoLojaRepository configs, ProdutoRepository produtos,
                             TaxaEntregaRepository taxas, HorarioFuncionamentoRepository horarios,
                             UsuarioRepository usuarios, AssinaturaRepository assinaturas,
                             IntegracaoCanalRepository integracoes, LojaRepository lojas, AuthContext ctx) {
        this.configs = configs;
        this.produtos = produtos;
        this.taxas = taxas;
        this.horarios = horarios;
        this.usuarios = usuarios;
        this.assinaturas = assinaturas;
        this.integracoes = integracoes;
        this.lojas = lojas;
        this.ctx = ctx;
    }

    public Map<String, Object> status() {
        Long lojaId = ctx.lojaId();

        ConfiguracaoLoja cfg = configs.findByLojaId(lojaId).orElse(null);
        boolean temMarca = cfg != null && cfg.logoUrl != null && !cfg.logoUrl.isBlank();
        boolean temCardapio = !produtos.findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc(lojaId).isEmpty();
        boolean temEntrega = !taxas.findByLojaIdOrderByBairroAsc(lojaId).isEmpty();
        boolean temHorario = !horarios.findByLojaIdOrderByDiaAsc(lojaId).isEmpty();
        boolean temEquipe = usuarios.countByLojaId(lojaId) > 1;

        Assinatura a = assinaturas.findByLojaId(lojaId).orElse(null);
        boolean pagamentoAtivo = a != null && a.getStatus() == StatusAssinatura.ATIVA;

        // Recebimento conta como pronto tanto pela subconta white-label quanto pela chave PIX legada.
        IntegracaoCanal pix = integracoes.findByLojaIdAndCanal(lojaId, "PIX").orElse(null);
        boolean pixLegado = pix != null && pix.clientSecret != null && !pix.clientSecret.isBlank();
        boolean subconta = lojas.findById(lojaId)
                .map(l -> l.asaasApiKey != null && !l.asaasApiKey.isBlank())
                .orElse(false);
        boolean recebimento = pixLegado || subconta;

        List<Map<String, Object>> passos = new ArrayList<>();
        passos.add(passo("marca", "Personalize a marca", "Logo e cor da sua loja", "configuracoes.html", true, temMarca));
        passos.add(passo("cardapio", "Monte o cardápio", "Ao menos 1 produto com preço", "produtos.html", true, temCardapio));
        passos.add(passo("entrega", "Defina a entrega", "Taxa de pelo menos 1 bairro", "ajustes.html", true, temEntrega));
        passos.add(passo("horario", "Confirme o horário", "Dias e horas de funcionamento", "ajustes.html", true, temHorario));
        passos.add(passo("pagamento", "Ative sua assinatura", "Cobrança da plataforma no Asaas", "planos.html", true, pagamentoAtivo));
        passos.add(passo("equipe", "Crie sua equipe", "Operador/gerente (opcional)", "usuarios.html", false, temEquipe));
        passos.add(passo("recebimento", "Receba por PIX", "Recebimento direto na sua conta", "integracoes.html", false, recebimento));

        int obrig = 0, obrigOk = 0;
        for (Map<String, Object> p : passos) {
            if (Boolean.TRUE.equals(p.get("obrigatorio"))) {
                obrig++;
                if (Boolean.TRUE.equals(p.get("concluido"))) obrigOk++;
            }
        }
        int prontidao = obrig == 0 ? 100 : Math.round(obrigOk * 100f / obrig);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prontidao", prontidao);
        out.put("operavel", obrigOk == obrig);
        out.put("passos", passos);
        return out;
    }

    private Map<String, Object> passo(String chave, String titulo, String descricao,
                                      String link, boolean obrigatorio, boolean concluido) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chave", chave);
        m.put("titulo", titulo);
        m.put("descricao", descricao);
        m.put("link", link);
        m.put("obrigatorio", obrigatorio);
        m.put("concluido", concluido);
        return m;
    }
}
