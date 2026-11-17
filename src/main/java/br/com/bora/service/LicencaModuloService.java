package br.com.bora.service;

import br.com.bora.entity.LicencaModulo;
import br.com.bora.entity.Modulo;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.repository.LicencaModuloRepository;
import br.com.bora.repository.LojaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O portão único: <b>este módulo está ligado para esta loja?</b>
 *
 * <h2>Por que isto existe</h2>
 * <p>Na fase C cada módulo do ERP é acoplado atrás da sua licença, um de cada vez. Se cada um
 * perguntar do seu jeito — uma chave global aqui, uma coluna booleana ali —, o décimo módulo terá
 * dez mecanismos diferentes e ninguém saberá dizer o que um cliente tem contratado. Esta classe é
 * a única resposta.</p>
 *
 * <h2>Hoje ela não muda nada, e isso é o ponto</h2>
 * <p>A tabela {@code licenca_modulo} nasceu vazia na V74. Sem linha, a resposta vem dos <b>dois
 * mecanismos que já funcionam em produção</b>:</p>
 * <ul>
 *   <li>{@code FISCAL} → {@code config_plataforma['fiscal.habilitado']} (global, lida pelo
 *       {@link FiscalService});</li>
 *   <li>{@code IA} → {@code loja.modulo_ia} (por loja, add-on pago).</li>
 * </ul>
 * <p>Os dois continuam valendo. O comportamento de hoje é bit a bit o de ontem — a diferença é que
 * agora existe UM lugar que sabe disso, e é ele que os módulos novos vão perguntar.</p>
 *
 * <h2>A precedência, e por que nesta ordem</h2>
 * <ol>
 *   <li><b>CORE e PEDIDOS são sempre sim.</b> São o módulo principal; não há o que licenciar.</li>
 *   <li><b>Linha em {@code licenca_modulo} decide</b>, quando existe. É o mecanismo novo, e ele
 *       ganha do antigo de propósito: é assim que a migração de um módulo acontece sem big-bang —
 *       grava-se a linha e aquela loja passa a ser governada pela tabela.</li>
 *   <li><b>Sem linha, o mecanismo antigo</b>, para FISCAL e IA.</li>
 *   <li><b>Qualquer outro, não.</b> Lacuna nunca vira "sim": módulo ligado por engano é pior que
 *       módulo desligado, porque o primeiro escreve no banco do cliente.</li>
 * </ol>
 *
 * <h2>O que esta classe NÃO faz</h2>
 * <p>Não substitui o {@code FiscalService.exigirHabilitado()}. A emissão de nota continua travada
 * pela chave global, que é o portão duro — e assim fica até o módulo Fiscal ser de fato acoplado.
 * Uma linha em {@code licenca_modulo} <b>não</b> liga emissão fiscal sozinha, e isso é deliberado:
 * ninguém deve conseguir emitir nota em nome de um cliente por ter gravado uma linha numa tabela
 * de licença.</p>
 */
@Service
public class LicencaModuloService {

    private final LicencaModuloRepository licencas;
    private final ConfigPlataformaRepository configs;
    private final LojaRepository lojas;

    public LicencaModuloService(LicencaModuloRepository licencas,
                                ConfigPlataformaRepository configs,
                                LojaRepository lojas) {
        this.licencas = licencas;
        this.configs = configs;
        this.lojas = lojas;
    }

    /**
     * A pergunta. Nunca lança: módulo desconhecido ou loja inexistente respondem <b>não</b>.
     *
     * <p>Lançar aqui faria uma lacuna de cadastro derrubar a tela que só queria saber se mostra um
     * menu. O erro certo para "não sei" é não.</p>
     */
    public boolean ligado(Long lojaId, Modulo modulo) {
        if (modulo == null) return false;
        if (modulo.sempreIncluido) return true;
        if (lojaId == null) return false;

        return licencas.findByLojaIdAndModulo(lojaId, modulo)
                .map(l -> Boolean.TRUE.equals(l.getAtivo()))
                .orElseGet(() -> peloMecanismoAntigo(lojaId, modulo));
    }

    /**
     * Compatibilidade com o que já está em produção. Enquanto não houver linha para a loja, é ele
     * que responde — e é o que garante que esta classe entrou sem mudar nada.
     */
    private boolean peloMecanismoAntigo(Long lojaId, Modulo modulo) {
        return switch (modulo) {
            // Global: o ADMINISTRADOR_BORA liga a emissão para a plataforma inteira.
            case FISCAL -> configs.findById("fiscal.habilitado")
                    .map(c -> Boolean.parseBoolean(c.getValor()))
                    .orElse(false);
            // Por loja: o add-on pago, liberado quando o cliente contrata.
            case IA -> lojas.findById(lojaId)
                    .map(l -> Boolean.TRUE.equals(l.moduloIa))
                    .orElse(false);
            // Os demais não existem ainda. Lacuna é não.
            default -> false;
        };
    }

    /** O quadro completo de uma loja — para a tela de cliente da plataforma e para diagnóstico. */
    public Map<Modulo, Boolean> situacao(Long lojaId) {
        Map<Modulo, Boolean> m = new LinkedHashMap<>();
        for (Modulo modulo : Modulo.values()) {
            m.put(modulo, ligado(lojaId, modulo));
        }
        return m;
    }

    /**
     * Grava a decisão para uma loja. A partir daí, é a linha que manda — o mecanismo antigo deixa
     * de ser consultado para aquele par (loja, módulo).
     *
     * <p>Quem chama é responsável por checar o papel: ligar módulo é decisão comercial da
     * plataforma, nunca do lojista. Esta classe não checa papel de propósito, para poder ser usada
     * também por rotina interna (provisionamento de cliente novo).</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public LicencaModulo definir(Long lojaId, Modulo modulo, boolean ativo, Long usuarioId, String motivo) {
        if (modulo.sempreIncluido) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O módulo " + modulo + " faz parte do principal e não se liga nem se desliga.");
        }
        // FISCAL e IA ainda sao governados pelos mecanismos antigos, e gravar linha aqui criaria
        // uma TERCEIRA opiniao que contradiz as outras duas -- em dinheiro:
        //   IA     -- o IaService e a cobranca leem loja.modulo_ia. Uma linha ativa faria este
        //             servico dizer "ligado" enquanto o add-on nao e cobrado; uma linha inativa
        //             diria "desligado" para uma loja que paga e usa.
        //   FISCAL -- a emissao segue a chave global. A linha nao liga nem desliga emissao nenhuma.
        // Enquanto a fonte de verdade for a antiga, recusar e mais honesto que gravar algo que nao
        // governa nada. Quando o modulo for de fato acoplado, a migracao desta regra vem junto.
        if (modulo == Modulo.FISCAL || modulo == Modulo.IA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O módulo " + modulo + " ainda é governado pelo mecanismo antigo ("
                            + (modulo == Modulo.IA ? "loja.modulo_ia" : "config_plataforma['fiscal.habilitado']")
                            + "). Mude por lá — gravar licença aqui criaria duas respostas diferentes "
                            + "para a mesma pergunta.");
        }
        if (lojaId == null || lojas.findById(lojaId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loja #" + lojaId + " não existe");
        }
        LicencaModulo l = licencas.findByLojaIdAndModulo(lojaId, modulo).orElseGet(() -> {
            LicencaModulo novo = new LicencaModulo();
            novo.setLojaId(lojaId);
            novo.setModulo(modulo);
            return novo;
        });
        l.setAtivo(ativo);
        if (ativo) {
            l.setAtivadoEm(java.time.OffsetDateTime.now());
            l.setAtivadoPor(usuarioId);
            l.setDesativadoEm(null);
            l.setMotivoDesativacao(null);
        } else {
            l.setDesativadoEm(java.time.OffsetDateTime.now());
            l.setDesativadoPor(usuarioId);
            l.setMotivoDesativacao(motivo);
        }
        return licencas.save(l);
    }
}
