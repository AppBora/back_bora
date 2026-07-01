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
        String endereco = join(str(end.get("streetName")), str(end.get("streetNumber")));
        return new InboundOrder(firstNonBlank(str(r.get("id")), str(r.get("externalId"))), str(cliente.get("name")),
                firstNonBlank(str(fone.get("number")), str(cliente.get("phone"))),
                endereco, str(end.get("neighborhood")), pagamentoIfood(r),
                str(r.get("observations")), firstNum(num(orderAmount.get("value")), num(total.get("value"))), itens);
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
    private InboundOrder noveNove(Map<String, Object> r) {
        Map<String, Object> cliente = mapOf(r.get("consumer"));
        Map<String, Object> end = mapOf(r.get("address"));
        List<InboundOrder.InboundItem> itens = new ArrayList<>();
        for (Object o : listOf(r.get("products"))) {
            Map<String, Object> i = mapOf(o);
            itens.add(new InboundOrder.InboundItem(firstNonBlank(str(i.get("name")), str(i.get("title"))),
                    intg(i.get("qty")), num(i.get("price"))));
        }
        return new InboundOrder(str(r.get("order_id")), firstNonBlank(str(cliente.get("name")), str(r.get("customer_name"))),
                firstNonBlank(str(cliente.get("phone")), str(r.get("phone"))),
                join(str(end.get("street")), str(end.get("number"))), str(end.get("district")),
                firstNonBlank(str(r.get("payment_method")), "Pago no app"),
                str(r.get("note")), firstNum(num(r.get("total_amount")), num(r.get("total"))), itens);
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
