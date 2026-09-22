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
    /**
     * Pedido do iFood (GET /order/v1.0/orders/{id}). Reescrito em 19/09/2026 sobre um pedido REAL da loja
     * de teste (consultado pela ferramenta de suporte), não sobre suposição: o total vem em número
     * (total.orderAmount) e o preço do item com complementos em totalPrice. Antes o Bora não achava o
     * total, somava só o preço base dos itens e um pedido de R$ 27,00 aparecia como R$ 10,00.
     *
     * <p>O que a homologação do iFood cobra na tela e vai escrito aqui: observação e complementos de cada
     * item, bandeira do cartão, troco, cupons e quem paga, retirada no balcão, pedido agendado.</p>
     */
    private InboundOrder ifood(Map<String, Object> r) {
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
                StringBuilder um = new StringBuilder(q != null && q > 1 ? q + "x " + n : n);
                List<String> sub = new ArrayList<>();
                for (Object cz : listOf(m.get("customizations"))) {
                    String cn = str(mapOf(cz).get("name"));
                    if (cn != null && !cn.isBlank()) sub.add(cn);
                }
                if (!sub.isEmpty()) um.append(" [").append(String.join(", ", sub)).append("]");
                opcoes.add(um.toString());
            }
            if (!opcoes.isEmpty()) nome.append(" (+ ").append(String.join(", ", opcoes)).append(")");
            String obsItem = str(i.get("observations"));
            if (obsItem != null && !obsItem.isBlank()) nome.append(" — obs: ").append(obsItem.trim());
            Integer qtd = intg(i.get("quantity"));
            BigDecimal unitario = num(i.get("unitPrice"));
            // Com complemento o preço base não fecha a conta: usa o total do item dividido pela quantidade.
            BigDecimal totalItem = num(i.get("totalPrice"));
            if (totalItem != null && qtd != null && qtd > 0) {
                unitario = totalItem.divide(BigDecimal.valueOf(qtd), 2, java.math.RoundingMode.HALF_UP);
            }
            itens.add(new InboundOrder.InboundItem(nome.toString(), qtd, unitario));
        }

        boolean retirada = "TAKEOUT".equalsIgnoreCase(str(r.get("orderType")));
        boolean entregaIfood = "IFOOD".equalsIgnoreCase(str(delivery.get("deliveredBy")));
        BigDecimal valorPedido = firstNum(num(total.get("orderAmount")), num(mapOf(total.get("orderAmount")).get("value")));
        BigDecimal taxa = num(total.get("deliveryFee"));
        BigDecimal pago = num(pagamentos.get("prepaid"));
        BigDecimal aPagar = num(pagamentos.get("pending"));

        List<String> obs = new ArrayList<>();
        String numero = str(r.get("displayId"));
        if (numero != null && !numero.isBlank()) obs.add("Pedido iFood #" + numero);
        if (retirada) {
            String hora = hora(str(mapOf(r.get("takeout")).get("takeoutDateTime")));
            obs.add("RETIRADA NO BALCÃO" + (hora == null ? "" : " — cliente vem buscar às " + hora));
        } else {
            obs.add(entregaIfood ? "Entrega pelo iFood" : "Entrega pela loja");
        }
        if ("SCHEDULED".equalsIgnoreCase(str(r.get("orderTiming")))) {
            String quando = hora(str(mapOf(r.get("schedule")).get("deliveryDateTimeStart")));
            obs.add("AGENDADO" + (quando == null ? "" : " para " + quando));
        }
        String obsPedido = str(r.get("observations"));
        if (obsPedido != null && !obsPedido.isBlank()) obs.add(obsPedido.trim());
        String obsEntrega = str(delivery.get("observations"));
        if (obsEntrega != null && !obsEntrega.isBlank()) obs.add("Entrega: " + obsEntrega.trim());
        for (Object b : listOf(r.get("benefits"))) {
            Map<String, Object> cupom = mapOf(b);
            BigDecimal valor = num(cupom.get("value"));
            if (valor == null || valor.signum() <= 0) continue;
            obs.add("Cupom " + reais(valor) + quemPagaCupom(cupom));
        }
        if (taxa != null && taxa.signum() > 0) obs.add("Taxa de entrega " + reais(taxa));
        String coleta = str(delivery.get("pickupCode"));
        if (entregaIfood && coleta != null && !coleta.isBlank()) obs.add("Código de coleta " + coleta);
        if (pago != null || aPagar != null) obs.add("Já pago " + reais(pago) + " · falta pagar " + reais(aPagar));

        String endereco = retirada ? null : juntarComVirgula(join(str(end.get("streetName")), str(end.get("streetNumber"))),
                str(end.get("complement")), str(end.get("reference")));

        return new InboundOrder(firstNonBlank(str(r.get("id")), str(r.get("externalId"))), str(cliente.get("name")),
                // O iFood manda a CENTRAL dele + um localizador: para falar com o cliente o entregador liga
                // na central e digita o código. Sem o código na tela o número não serve para nada.
                comLocalizador(firstNonBlank(str(fone.get("number")), str(cliente.get("phone"))), str(fone.get("localizer"))),
                endereco, retirada ? null : str(end.get("neighborhood")), pagamentoIfood(pagamentos),
                String.join(" | ", obs), valorPedido, itens, taxa, numero)
                .comClienteExterno(str(cliente.get("id")));
    }

    private static String comLocalizador(String numero, String localizador) {
        if (numero == null || localizador == null || localizador.isBlank()) return numero;
        return numero + " · código " + localizador.trim();
    }

    @SuppressWarnings("unchecked")
    /** Cada forma de pagamento com bandeira, se é online ou na entrega, e o troco do dinheiro. */
    private String pagamentoIfood(Map<String, Object> pagamentos) {
        List<String> partes = new ArrayList<>();
        List<Object> metodos = listOf(pagamentos.get("methods"));
        for (Object o : metodos) {
            Map<String, Object> m = mapOf(o);
            String metodo = str(m.get("method"));
            String nome = nomeDoMetodoIfood(metodo);
            String bandeira = str(mapOf(m.get("card")).get("brand"));
            if (bandeira != null && !bandeira.isBlank()) nome += " " + bandeira.trim();
            boolean online = Boolean.TRUE.equals(m.get("prepaid")) || "ONLINE".equalsIgnoreCase(str(m.get("type")));
            BigDecimal valor = num(m.get("value"));
            String texto;
            if (online) {
                texto = "Pago online: " + nome;
            } else if ("CASH".equalsIgnoreCase(metodo)) {
                BigDecimal troco = num(mapOf(m.get("cash")).get("changeFor"));
                texto = "Dinheiro na entrega" + (troco != null && troco.signum() > 0 ? " — troco para " + reais(troco) : " — sem troco");
            } else {
                texto = "Na entrega: " + nome + " (maquininha)";
            }
            if (valor != null && metodos.size() > 1) texto += " " + reais(valor);
            partes.add(texto);
        }
        return partes.isEmpty() ? "Pago no app" : String.join(" + ", partes);
    }

    private String nomeDoMetodoIfood(String m) {
        if (m == null) return "pagamento";
        return switch (m.toUpperCase()) {
            case "CREDIT" -> "Crédito";
            case "DEBIT" -> "Débito";
            case "MEAL_VOUCHER" -> "Vale-refeição";
            case "FOOD_VOUCHER" -> "Vale-alimentação";
            case "PIX" -> "PIX";
            case "CASH" -> "Dinheiro";
            case "DIGITAL_WALLET" -> "Carteira digital";
            case "GIFT_CARD" -> "Vale-presente";
            default -> m.toLowerCase();
        };
    }

    /** Checklist do iFood: mostrar quem paga o cupom — o iFood ou a loja. */
    private String quemPagaCupom(Map<String, Object> cupom) {
        List<String> quem = new ArrayList<>();
        for (Object sp : listOf(cupom.get("sponsorshipValues"))) {
            Map<String, Object> s = mapOf(sp);
            BigDecimal v = num(s.get("value"));
            if (v != null && v.signum() == 0) continue;
            String n = str(s.get("name"));
            String rotulo = "IFOOD".equalsIgnoreCase(n) ? "pago pelo iFood"
                    : "MERCHANT".equalsIgnoreCase(n) ? "pago pela loja"
                    : "CHAIN".equalsIgnoreCase(n) ? "pago pela rede"
                    : n == null ? null : "pago por " + n.toLowerCase();
            if (rotulo != null && !quem.contains(rotulo)) quem.add(rotulo);
        }
        return quem.isEmpty() ? "" : " (" + String.join(", ", quem) + ")";
    }

    /** "dd/MM HH:mm" no horário de Brasília, a partir do ISO que o iFood manda. */
    private String hora(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.of("America/Sao_Paulo"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"));
        } catch (Exception e) {
            return null;
        }
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

        // Retirada (Open Delivery type TAKEOUT; a 99 liberou em 22/09): o objeto delivery nao vem e vem
        // takeout {mode, takeoutDateTime}. Sem isso o pedido aparecia como "Entrega pela loja".
        boolean retirada = "TAKEOUT".equalsIgnoreCase(str(r.get("type")));
        boolean pelaPlataforma = !retirada && "MARKETPLACE".equalsIgnoreCase(str(delivery.get("deliveredBy")));
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

        String endereco = retirada ? null : juntarComVirgula(join(str(end.get("street")), str(end.get("number"))),
                str(end.get("complement")), str(end.get("reference")));

        List<String> obs = new ArrayList<>();
        String numero = str(r.get("displayId"));
        if (numero != null && !numero.isBlank()) obs.add("Pedido 99 #" + numero);
        if (retirada) {
            String quando = hora(str(mapOf(r.get("takeout")).get("takeoutDateTime")));
            obs.add("RETIRADA NO BALCÃO" + (quando == null ? "" : " — cliente vem buscar às " + quando));
        } else {
            obs.add(pelaPlataforma ? "Entrega pela 99" : "Entrega pela loja");
        }
        String extra = str(r.get("extraInfo"));
        if (extra != null && !extra.isBlank()) obs.add("Obs: " + extra.trim());
        if (desconto != null && desconto.signum() > 0) obs.add("Desconto " + reais(desconto) + quemDeu(r));
        if (taxa != null && taxa.signum() > 0) obs.add("Taxa de entrega " + reais(taxa));
        obs.add("Ja pago " + reais(pago) + " · falta pagar " + reais(aPagar));

        return new InboundOrder(str(r.get("id")),
                firstNonBlank(str(cliente.get("name")), "Cliente 99Food"),
                str(fone.get("number")),
                endereco, retirada ? null : str(end.get("district")),
                pagamentoOpenDelivery(pagamentos, pelaPlataforma, aPagar),
                String.join(" | ", obs), valorPedido, itens, taxa, numero)
                .comClienteExterno(str(cliente.get("id")));
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
