package br.com.bora.dto;

import java.math.BigDecimal;

/**
 * Mexer no estoque de um produto ou insumo — a operação separada do cadastro.
 *
 * <h2>O defeito que isto fecha (medido em 20/10/2026)</h2>
 * <p>Estoque era um campo do formulário de cadastro, e o {@code PUT /api/produtos/{id}} gravava
 * {@code p.estoque = dados.estoque} sem condição. O lojista abria Produtos às 18h, a loja vendia 12
 * durante a noite, ele corrigia só o <b>preço</b> às 21h — e as 12 baixas desapareciam, sem log e sem
 * aviso. A tela de Estoque e a de Contagem usavam o mesmo endpoint, mandando o cadastro inteiro de
 * volta a cada ajuste, então também apagavam o {@code codigo_externo} que elas não conhecem.</p>
 *
 * <h2>Por que um endpoint separado e não um campo protegido</h2>
 * <p>São duas operações diferentes que passavam pelo mesmo cano: "editar o cadastro do produto" e
 * "dizer quanto tem na prateleira". Enquanto as duas compartilharem a mesma requisição, qualquer tela
 * que mande um cadastro incompleto apaga o que não conhece — foi assim que o estoque sumia, e foi
 * assim que o código do marketplace ia sumir. Separando, o cadastro <b>não tem como</b> mexer no
 * estoque, e cada ajuste diz exatamente o que quer.</p>
 *
 * @param tipo       {@code DEFINIR} ou {@code AJUSTAR}. Obrigatório, e de propósito: sem ele,
 *                   {@code quantidade: null} seria ambíguo entre "para de controlar estoque" e
 *                   "não mandei nada".
 * @param quantidade em {@code DEFINIR}, o valor contado — {@code null} quer dizer <b>para de
 *                   controlar estoque</b>, que é o "vazio = não controla" da tela. Em
 *                   {@code AJUSTAR}, quanto somar (negativo tira), e aí não pode ser nulo.
 */
public record MexerNoEstoqueRequest(Tipo tipo, BigDecimal quantidade) {

    public enum Tipo {
        /**
         * Troca o saldo pelo valor informado. É o que a Contagem faz: o lojista andou na prateleira e
         * contou, então o número dele é a verdade, não importa o que o sistema dizia.
         */
        DEFINIR,
        /**
         * Soma ao saldo, <b>no banco</b>. É o que os botões + e − fazem. Antes a conta era no
         * navegador, a partir de uma lista carregada minutos antes: clicar "+1" às 21h com a lista de
         * 18h gravava o saldo de 18h mais um, e apagava as vendas da noite.
         */
        AJUSTAR
    }
}
