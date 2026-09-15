package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Traduz o payload bruto de cada marketplace para o pedido canônico ({@link InboundOrder}).
 * Cada adaptador tenta os nomes de campo mais comuns do parceiro e cai para nomes canônicos.
 * Quando as credenciais reais entrarem, só os caminhos de campo podem precisar de ajuste fino.
 */
@Component
public class MarketplaceNormalizer {

    @SuppressWarnings("unchecked")
    public InboundOrder normalizar(String canal, Map<String, Object> raw) {
        if (raw == null) raw = Map.of();
        String c = canal == null ? "" : canal.toUpperCase();
        return switch (c) {
            case "IFOOD" -> ifood(raw);
            case "NOVE_NOVE", "99FOOD", "99" -> noveNove(raw);
            case "RAPPI" -> rappi(raw);
            case "UBER_EATS", "UBEREATS" -> uberEats(raw);
            default -> generico(raw); // AiQFome, Goomer, site próprio, testes
        };
    }

    // ---------- iFood (estrutura v3) ----------
    @SuppressWarnings("unchecked")
    private InboundOrder ifood(Map<String, Object> r) {
        Map<String, Object> cliente = mapOf(r.get("customer"));
        Map<String, Object> fone = mapOf(cliente.get("phone"));
        Map<String, Object> delivery = mapOf(r.get("delivery"));
        Map<String, Object> end = mapOf(delivery.get("deliveryAddress"));
        Map<String, Object> total = mapOf(r.get("total"));
        Map<String, Object> orderAmount = mapOf(total.get("orderAmount"));
        List<InboundOrder.InboundItem> itens = new ArrayList<>();
        for (Object o : listOf(r.get("items"))) {
            Map<String, Object> i = mapOf(o);
            itens.add(new InboundOrder.InboundItem(str(i.get("name")), intg(i.get("quantity")), num(i.get("unitPrice"))));
        }
        // Complemento e referencia decidem se o entregador acha a casa; sem eles o endereco vira
        // so "rua e numero" e o motoboy liga para o cliente.
        String endereco = join(str(end.get("streetName")), str(end.get("streetNumber")));
        endereco = juntarComVirgula(endereco, str(end.get("complement")), str(end.get("reference")));
        return new InboundOrder(firstNonBlank(str(r.get("id")), str(r.get("externalId"))), str(cliente.get("name")),
                firstNonBlank(str(fone.get("number")), str(cliente.get("phone"))),
                endereco, str(end.get("neighborhood")), pagamentoIfood(r),
                observacaoIfood(r, delivery), firstNum(num(orderAmount.get("value")), num(total.get("value"))), itens);
    }

    @SuppressWarnings("unchecked")
    private String pagamentoIfood(Map<String, Object> r) {
        for (Object o : listOf(mapOf(r.get("payments")).get("methods"))) {
            Map<String, Object> m = mapOf(o);
            String t = str(m.get("method")); if (t != null) return t;
        }
        return "Pago no app";
    }

    // ---------- 99Food ----------
    /**
     * 99Food no padrao Open Delivery v1.7.1 (GET /v1/orders/{id}). O parser anterior, de 30/06, lia
     * um snake_case que a 99 nunca mandou (order_id, consumer, products, qty): todo pedido real
     * entraria em branco. Os campos abaixo sao os da especificacao oficial.
     *
     * <p>A checklist de homologacao da 99 cobra ver no pedido: valor dos itens, da entrega,
     * descontos (e de quem), quanto ja foi pago, quanto falta, quem entrega e a observacao. O que
     * nao tem coluna propria no pedido vai escrito na observacao e no pagamento — que e o que
     * aparece na tela e na comanda.</p>
     */
    private InboundOrder noveNove(Map<String, Object> r) {
        Map<String, Object> cliente = mapOf(r.get("customer"));
        Map<String, Object> fone = mapOf(cliente.get("phone"));
        Map<String, Object> delivery = mapOf(r.get("delivery"));
        Map<String, Object> end = mapOf(delivery.get("deliveryAddress"));
        Map<String, Object> total = mapOf(r.get("total"));
        Map<String, Object> pagamentos = mapOf(r.get("payments"));

        List<InboundOrder.InboundItem> itens = new ArrayList<>();
        for (Object o : listOf(r.get("items"))) {
            Map<String, Object> i = mapOf(o);
            StringBuilder nome = new StringBuilder(firstNonBlank(str(i.get("name")), "Item"));
            List<String> opcoes = new ArrayList<>();
            for (Object op : listOf(i.get("options"))) {
                Map<String, Object> m = mapOf(op);
                String n = str(m.get("name"));
                if (n == null || n.isBlank()) continue;
                Integer q = intg(m.get("quantity"));
                opcoes.add(q != null && q > 1 ? q + "x " + n : n);
            }
            if (!opcoes.isEmpty()) nome.append(" (+ ").append(String.join(", ", opcoes)).append(")");
            String instrucao = str(i.get("specialInstructions"));
            if (instrucao != null && !instrucao.isBlank()) nome.append(" — obs: ").append(instrucao.trim());
            Integer qtd = intg(i.get("quantity"));
            BigDecimal unitario = preco(i.get("unitPrice"));
            // Com opcional pago o unitario sozinho nao fecha a conta: usa o total do item dividido.
            BigDecimal totalItem = preco(i.get("totalPrice"));
            if (totalItem != null && qtd != null && qtd > 0) {
                unitario = totalItem.divide(BigDecimal.valueOf(qtd), 2, java.math.RoundingMode.HALF_UP);
            }
            itens.add(new InboundOrder.InboundItem(nome.toString(), qtd, unitario));
        }

        boolean pelaPlataforma = "MARKETPLACE".equalsIgnoreCase(str(delivery.get("deliveredBy")));
        BigDecimal valorPedido = preco(total.get("orderAmount"));
        BigDecimal taxa = preco(total.get("otherFees"));
        BigDecimal desconto = preco(total.get("discount"));
        BigDecimal pago = num(pagamentos.get("prepaid"));
        BigDecimal aPagar = num(pagamentos.get("pending"));
        // Roteiro da 99, pag. 21: com entrega pela plataforma a loja recebe tudo online, mesmo
        // pedido pago em dinheiro — nao ha nada a cobrar do entregador.
        if (pelaPlataforma) {
            pago = valorPedido;
            aPagar = BigDecimal.ZERO;
        }

        String endereco = juntarComVirgula(join(str(end.get("street")), str(end.get("number"))),
                str(end.get("complement")), str(end.get("reference")));

        List<String> obs = new ArrayList<>();
        String numero = str(r.get("displayId"));
        if (numero != null && !numero.isBlank()) obs.add("Pedido 99 #" + numero);
        obs.add(pelaPlataforma ? "Entrega pela 99" : "Entrega pela loja");
        String extra = str(r.get("extraInfo"));
        if (extra != null && !extra.isBlank()) obs.add("Obs: " + extra.trim());
        if (desconto != null && desconto.signum() > 0) obs.add("Desconto " + reais(desconto) + quemDeu(r));
        if (taxa != null && taxa.signum() > 0) obs.add("Taxa de entrega " + reais(taxa));
        obs.add("Ja pago " + reais(pago) + " · falta pagar " + reais(aPagar));

        return new InboundOrder(str(r.get("id")),
                firstNonBlank(str(cliente.get("name")), "Cliente 99Food"),
                str(fone.get("number")),
                endereco, str(end.get("district")),
                pagamentoOpenDelivery(pagamentos, pelaPlataforma, aPagar),
                String.join(" | ", obs), valorPedido, itens, taxa, numero);
    }

    /** Como o cliente paga, do jeito que o caixa e o entregador precisam ler. */
    private String pagamentoOpenDelivery(Map<String, Object> pagamentos, boolean pelaPlataforma, BigDecimal aPagar) {
        if (pelaPlataforma || aPagar == null || aPagar.signum() == 0) return "Pago online (99Food)";
        for (Object o : listOf(pagamentos.get("methods"))) {
            Map<String, Object> m = mapOf(o);
            if (!"PENDING".equalsIgnoreCase(str(m.get("type")))) continue;
            String metodo = str(m.get("method"));
            if ("CASH".equalsIgnoreCase(metodo)) {
                BigDecimal troco = num(m.get("changeFor"));
                return troco != null && troco.signum() > 0
                        ? "Dinheiro na entrega — troco para " + reais(troco)
                        : "Dinheiro na entrega";
            }
            return "Na entrega: " + nomeDoMetodo(metodo);
        }
        return "Na entrega: " + reais(aPagar);
    }

    private String nomeDoMetodo(String m) {
        if (m == null) return "a combinar";
        return switch (m.toUpperCase()) {
            case "CREDIT" -> "cartao de credito";
            case "DEBIT" -> "cartao de debito";
            case "CREDIT_DEBIT" -> "cartao";
            case "MEAL_VOUCHER" -> "vale-refeicao";
            case "FOOD_VOUCHER" -> "vale-alimentacao";
            case "PIX" -> "PIX";
            default -> m.toLowerCase();
        };
    }

    /** Checklist da 99: distinguir cupom dado pela loja do cupom dado pela 99. */
    private String quemDeu(Map<String, Object> r) {
        List<String> quem = new ArrayList<>();
        for (Object d : listOf(r.get("discounts"))) {
            for (Object sp : listOf(mapOf(d).get("sponsorshipValues"))) {
                String n = str(mapOf(sp).get("name"));
                String rotulo = "MARKETPLACE".equalsIgnoreCase(n) ? "pago pela 99"
                        : "MERCHANT".equalsIgnoreCase(n) ? "pago pela loja"
                        : "CHAIN".equalsIgnoreCase(n) ? "pago pela rede" : null;
                if (rotulo != null && !quem.contains(rotulo)) quem.add(rotulo);
            }
        }
        return quem.isEmpty() ? "" : " (" + String.join(", ", quem) + ")";
    }

    /** Price do Open Delivery e {value, currency}; aceita numero solto por robustez. */
    private BigDecimal preco(Object o) {
        Map<String, Object> m = mapOf(o);
        return m.isEmpty() ? num(o) : num(m.get("value"));
    }

    private String reais(BigDecimal v) {
        BigDecimal x = v == null ? BigDecimal.ZERO : v.setScale(2, java.math.RoundingMode.HALF_UP);
        return "R$ " + x.toPlainString().replace('.', ',');
    }

    // ---------- Rappi ----------
    private InboundOrder rappi(Map<String, Object> r) {
        Map<String, Object> cliente = mapOf(r.get("client"));
        Map<String, Object> end = mapOf(r.get("delivery_address"));
        List<InboundOrder.InboundItem> itens = new ArrayList<>();
        for (Object o : listOf(r.get("items"))) {
            Map<String, Object> i = mapOf(o);
            itens.add(new InboundOrder.InboundItem(str(i.get("name")), intg(i.get("units")), num(i.get("unit_price"))));
        }
        return new InboundOrder(str(r.get("order_id")), firstNonBlank(str(cliente.get("first_name")), str(cliente.get("name"))),
                str(cliente.get("phone")), str(end.get("address")), str(end.get("neighborhood")),
                firstNonBlank(str(r.get("payment_method")), "Pago no app"),
                str(r.get("comments")), firstNum(num(r.get("total_value")), num(r.get("total"))), itens);
    }

    // ---------- Uber Eats ----------
    private InboundOrder uberEats(Map<String, Object> r) {
        Map<String, Object> cart = mapOf(r.get("cart"));
        Map<String, Object> eater = mapOf(r.get("eater"));
        Map<String, Object> end = mapOf(r.get("deliveryLocation"));
        Map<String, Object> payment = mapOf(r.get("payment"));
        Map<String, Object> charges = mapOf(payment.get("charges"));
        Map<String, Object> totalCharge = mapOf(charges.get("total"));
        List<InboundOrder.InboundItem> itens = new ArrayList<>();
        for (Object o : listOf(cart.get("items"))) {
            Map<String, Object> i = mapOf(o);
            Map<String, Object> price = mapOf(mapOf(i.get("price")).get("unitPrice"));
            itens.add(new InboundOrder.InboundItem(str(i.get("title")), intg(i.get("quantity")),
                    firstNum(num(price.get("amount")), num(i.get("price")))));
        }
        return new InboundOrder(str(r.get("id")), str(eater.get("firstName")), str(eater.get("phone")),
                str(end.get("streetAddress")), str(end.get("neighborhood")), "Pago no app",
                str(r.get("specialInstructions")), firstNum(num(totalCharge.get("amount")), num(r.get("total"))), itens);
    }

    // ---------- Genérico / canônico (AiQFome, Goomer, site, testes) ----------
    /**
     * O iFood tem DOIS campos de observacao: a do pedido ("sem cebola") e a da ENTREGA
     * ("interfone quebrado"), dentro de delivery. Ler so a primeira - como estava - perde a segunda,
     * e exibir a observacao de entrega e criterio de homologacao do modulo de pedidos.
     */
    private String observacaoIfood(Map<String, Object> r, Map<String, Object> delivery) {
        String doPedido = str(r.get("observations"));
        String daEntrega = str(delivery.get("observations"));
        if (daEntrega == null || daEntrega.isBlank()) return doPedido;
        String prefixado = "Entrega: " + daEntrega;
        return doPedido == null || doPedido.isBlank() ? prefixado : doPedido + " | " + prefixado;
    }

    private String juntarComVirgula(String base, String... extras) {
        StringBuilder sb = new StringBuilder(base == null ? "" : base);
        for (String e : extras) {
            if (e == null || e.isBlank()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.trim());
        }
        return sb.toString();
    }

    private InboundOrder generico(Map<String, Object> r) {
        List<InboundOrder.InboundItem> itens = new ArrayList<>();
        for (Object o : listOf(r.get("itens"))) {
            Map<String, Object> i = mapOf(o);
            itens.add(new InboundOrder.InboundItem(firstNonBlank(str(i.get("nome")), str(i.get("name"))),
                    firstInt(intg(i.get("quantidade")), intg(i.get("quantity"))),
                    firstNum(num(i.get("precoUnitario")), num(i.get("preco")))));
        }
        return new InboundOrder(firstNonBlank(str(r.get("externalId")), str(r.get("id"))),
                firstNonBlank(str(r.get("clienteNome")), str(r.get("cliente")), str(r.get("customer"))),
                firstNonBlank(str(r.get("clienteTelefone")), str(r.get("telefone")), str(r.get("phone"))),
                str(r.get("endereco")), str(r.get("bairro")),
                firstNonBlank(str(r.get("pagamento")), "Pago no app"),
                str(r.get("observacao")), num(r.get("total")), itens);
    }

    // ---------- helpers ----------
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }
    @SuppressWarnings("unchecked")
    private List<Object> listOf(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Integer intg(Object o) { try { return o == null ? null : (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; } }
    private BigDecimal num(Object o) { try { return o == null ? null : new BigDecimal(String.valueOf(o)); } catch (Exception e) { return null; } }
    private String firstNonBlank(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return null; }
    private BigDecimal firstNum(BigDecimal... v) { for (BigDecimal b : v) if (b != null) return b; return null; }
    private Integer firstInt(Integer... v) { for (Integer i : v) if (i != null) return i; return null; }
    private String join(String a, String b) { return firstNonBlank(a, "") + (b != null && !b.isBlank() ? ", " + b : ""); }
}
