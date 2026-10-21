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

    private LarguraDeColuna() {
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
}
