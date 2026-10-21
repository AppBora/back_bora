package br.com.bora.entity;

/**
 * A largura das colunas de texto que recebem valor de FORA — marketplace, cardápio, webhook.
 *
 * <h2>Por que estes números moram num lugar só</h2>
 * <p>O {@code ddl-auto: validate} do Hibernate confere se a coluna existe e qual o tipo. <b>Não
 * confere a largura.</b> Então uma coluna de 255 recebendo um texto de 300 passa por toda a subida da
 * aplicação, por todo teste de serviço com mock, e só falha no Postgres — na hora em que um pedido de
 * verdade chega.</p>
 *
 * <p>E não falha truncando: o Postgres <b>recusa o INSERT</b>
 * ({@code value too long for type character varying(255)}), o pedido inteiro se perde, e o entregador
 * do iFood está a caminho de um pedido que o painel nunca viu.</p>
 *
 * <p>Foi o que aconteceu com {@code pedido.endereco_entrega}: o MESMO endereço do marketplace ia
 * cortado em 500 para {@code cliente.endereco} (varchar(500) desde a V37, largada justamente por
 * isso) e <b>sem corte</b> para uma coluna de 255.</p>
 *
 * <h2>Como isto para de acontecer</h2>
 * <p>Quem grava valor de fora corta por estas constantes, e o
 * {@code LarguraDasColunasBateComOBancoIT} compara cada uma com a largura <b>real</b> do banco. Uma
 * migração que mude a largura sem atualizar a constante — ou o contrário — reprova.</p>
 *
 * <p>Truncar perde informação, e perder o fim de um endereço é ruim. Mas é muito menos ruim que
 * perder a venda: 500 caracteres de endereço são suficientes para entregar, e quem corta avisa no
 * log.</p>
 */
public final class LarguraDeColuna {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LarguraDeColuna.class);

    private LarguraDeColuna() {
    }

    /**
     * Corta o texto para caber na coluna, avisando no log quando corta.
     *
     * <p>Mesma regra do {@code pedido.endereco_entrega}, pelo mesmo motivo: o Postgres <b>recusa</b> o
     * INSERT quando o texto nao cabe, e nessa hora quem se perde e a gravacao INTEIRA, nao o campo.
     * Perder o fim de um texto e ruim; perder o registro de um pagamento ou o registro de uma falha e
     * pior.</p>
     *
     * <p>E avisa sempre: corte silencioso e informacao que desaparece sem deixar rastro. O
     * {@code onde} identifica o lugar para quem ler o log — a coluna e, quando faz diferenca, a loja
     * ou o id.</p>
     *
     * @return o texto cortado, ou o proprio texto quando cabe; {@code null} para nulo e vazio
     */
    public static String encurtar(String valor, int max, String onde) {
        if (valor == null) return null;
        String limpo = valor.trim();
        if (limpo.isEmpty()) return null;
        if (limpo.length() <= max) return limpo;
        log.warn("{}: o texto tinha {} caracteres e foi CORTADO em {}. A gravacao aconteceu. "
                + "Texto completo: {}", onde, limpo.length(), max, limpo);
        return limpo.substring(0, max);
    }

    // ---- pedido
    /** {@code pedido.endereco_entrega}. Igual ao do cliente de propósito: é o mesmo valor. */
    public static final int PEDIDO_ENDERECO = 500;
    /** {@code pedido.bairro_entrega}. Igual ao do cliente. */
    public static final int PEDIDO_BAIRRO = 100;
    /** {@code pedido.id_externo} — o id do pedido no marketplace. */
    public static final int PEDIDO_ID_EXTERNO = 160;
    /** {@code pedido.forma_pagamento}. */
    public static final int PEDIDO_FORMA_PAGAMENTO = 255;
    /** {@code pedido.cliente_telefone}. */
    public static final int PEDIDO_TELEFONE = 60;
    /** {@code pedido.codigo} — o número que aparece no card. */
    public static final int PEDIDO_CODIGO = 40;

    // ---- cliente
    /** {@code cliente.endereco}. */
    public static final int CLIENTE_ENDERECO = 500;
    /** {@code cliente.bairro}. */
    public static final int CLIENTE_BAIRRO = 100;
    /** {@code cliente.nome}. */
    public static final int CLIENTE_NOME = 160;
    /** {@code cliente.telefone} — mais estreita que a do pedido, e é o menor dos dois que vale. */
    public static final int CLIENTE_TELEFONE = 40;
    /** {@code cliente.id_externo}. */
    public static final int CLIENTE_ID_EXTERNO = 160;

    // ---- cobranca da plataforma
    /**
     * {@code pagamento_assinatura.descricao} — nasce com o nome da loja e recebe a observacao do
     * registro manual concatenada.
     *
     * <p>Item 4.4 da varredura: a observacao vinha crua do corpo da requisicao, e o nome da loja
     * sozinho pode ocupar 160 dos 200 ({@code loja.nome} e varchar(160)). O metodo e
     * {@code @Transactional}: a observacao longa nao cortava o texto, <b>derrubava o registro inteiro
     * do pagamento</b> — e e a funcao que o dono usa para dar baixa em dinheiro recebido fora do
     * Asaas.</p>
     */
    public static final int PAGAMENTO_DESCRICAO = 200;

    // ---- marketplaces
    /**
     * {@code integracao_canal.ultimo_erro} — a mensagem de falha que a tela de Integracoes mostra.
     *
     * <p>Item 4.5 da varredura. Medido em 21/10/2026, dos cinco lugares que gravam esta coluna so
     * <b>um</b> estava de fato sem limite ({@code IfoodClient}, na renovacao do token, com o
     * {@code e.getMessage()} cru); os outros passavam por um corte em 400 ou por texto fixo. Mas
     * "seguro por sorte" nao e seguro: a gravacao acontece <b>dentro do tratamento de erro</b>, entao
     * se ela estoura a falha nunca e registrada e a tela mostra a integracao como se nada tivesse
     * acontecido — o pior lugar possivel para um estouro.</p>
     */
    public static final int INTEGRACAO_ULTIMO_ERRO = 500;
}
