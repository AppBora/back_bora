package br.com.bora.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regras que fazem, de graça, o que a IA fazia caro.
 *
 * <p>Boa parte do que o agente "descobria" é aritmética: hora fraca é faixa com pedido abaixo da
 * média, ruptura é estoque dividido por venda diária, canal caro é comissão sobre faturamento.
 * Isso não precisa de modelo de linguagem — precisa de limiar e conta. Cada chamada de IA custava
 * ~5.700 tokens de entrada para redescobrir a mesma divisão.</p>
 *
 * <p>O motor devolve <b>o mesmo formato</b> das recomendações do agente, então a tela mostra as duas
 * coisas nos mesmos cartões. A diferença é a origem: {@code origem: "REGRA"} sai daqui e é gratuito;
 * {@code "IA"} sai do Claude e custa. O lojista vê os sinais assim que abre a tela; a IA vira um
 * segundo passo opcional, para quando ele quiser leitura de contexto e priorização escrita.</p>
 *
 * <p>Os limiares são constantes com nome, não números soltos no meio do código: quando a Zirá disser
 * que 5% de cancelamento é normal para ela, muda-se um lugar só.</p>
 */
@Service
public class MotorDeSinais {

    /** Faixa com menos que isto da média das horas abertas é buraco de venda, não movimento fraco. */
    private static final double PISO_HORA_FRACA = 0.40;
    /** Cancelamento aceitável. Acima disso há causa operacional, não azar. */
    private static final BigDecimal TETO_CANCELAMENTO_PCT = BigDecimal.valueOf(5);
    /** Motivo que responde por esta fatia dos cancelamentos é o problema, não um entre vários. */
    private static final double FATIA_MOTIVO_DOMINANTE = 0.30;
    /** Menos que isto de cobertura não dá tempo de comprar e receber. */
    private static final BigDecimal DIAS_RUPTURA = BigDecimal.valueOf(3);
    /** Comissão acima desta fatia do faturamento come a margem inteira de delivery. */
    private static final BigDecimal TETO_COMISSAO_PCT = BigDecimal.valueOf(20);
    /** Etapa que passa disso em média trava a operação e vira cancelamento. */
    private static final BigDecimal TETO_MINUTOS_ETAPA = BigDecimal.valueOf(20);
    /** Produto ativo que não chega a isto no período está ocupando espaço na vitrine. */
    private static final int VENDA_MINIMA_PRODUTO = 3;
    /** Ticket abaixo desta fatia da média da rede indica cardápio ou operação diferente. */
    private static final double PISO_TICKET_RELATIVO = 0.80;

    /** Lê o dossiê e devolve os sinais achados, do mais caro para o mais barato. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> sinais(Map<String, Object> dossie) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> balancete = (Map<String, Object>) dossie.get("balancete");
        Map<String, Object> canais = (Map<String, Object>) dossie.get("canais");
        Map<String, Object> horario = (Map<String, Object>) dossie.get("horario");
        Map<String, Object> tempos = (Map<String, Object>) dossie.get("tempos");

        horaFraca(horario, out);
        cancelamentoPorLoja(tempos, out);
        motivoDominante(tempos, out);
        ruptura((List<Map<String, Object>>) dossie.get("estoque"), out);
        canalCaro(canais, out);
        gargalo(tempos, out);
        produtoEncalhado(canais, out);
        lojaComTicketBaixo(balancete, out);

        out.sort(Comparator.comparingInt(m -> ordem((String) m.get("impacto"))));
        return out;
    }

    private static int ordem(String impacto) {
        return "ALTO".equals(impacto) ? 0 : "MEDIO".equals(impacto) ? 1 : 2;
    }

    private static Map<String, Object> sinal(String categoria, String impacto, String titulo,
                                             String porque, String comoFazer, String tela, String rotulo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origem", "REGRA");
        m.put("categoria", categoria);
        m.put("impacto", impacto);
        m.put("titulo", titulo);
        m.put("porque", porque);
        m.put("comoFazer", comoFazer);
        m.put("ganhoEstimado", null); // estimar ganho é chute; a regra só afirma o que mediu
        m.put("acao", Map.of("tela", tela, "rotulo", rotulo));
        return m;
    }

    private static BigDecimal num(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private static String money(Object v) {
        return "R$ " + num(v).setScale(2, RoundingMode.HALF_UP).toString().replace('.', ',');
    }

    // ------------------------------------------------------------------ regras

    /** Faixa de horário aberta e vazia: custo fixo rodando sem venda. */
    @SuppressWarnings("unchecked")
    private void horaFraca(Map<String, Object> horario, List<Map<String, Object>> out) {
        if (horario == null) return;
        for (String chave : List.of("diasUteis", "fimSemana")) {
            List<Map<String, Object>> faixas = (List<Map<String, Object>>) horario.get(chave);
            if (faixas == null || faixas.isEmpty()) continue;
            // "hora aberta" = hora em que a loja já vendeu alguma vez; hora sem venda nenhuma no
            // período costuma ser loja fechada, e apontar isso como buraco seria ruído.
            List<Map<String, Object>> abertas = faixas.stream()
                    .filter(f -> num(f.get("pedidos")).signum() > 0).toList();
            if (abertas.size() < 4) continue; // amostra curta demais para falar de queda
            double media = abertas.stream().mapToDouble(f -> num(f.get("pedidos")).doubleValue()).average().orElse(0);
            if (media <= 0) continue;
            List<Map<String, Object>> fracas = abertas.stream()
                    .filter(f -> num(f.get("pedidos")).doubleValue() < media * PISO_HORA_FRACA)
                    .sorted(Comparator.comparing(f -> num(f.get("hora"))))
                    .toList();
            if (fracas.isEmpty()) continue;
            String faixaTexto = fracas.stream().map(f -> num(f.get("hora")).intValue() + "h")
                    .reduce((a, b) -> a + ", " + b).orElse("");
            int pedidosFracos = fracas.stream().mapToInt(f -> num(f.get("pedidos")).intValue()).sum();
            String periodo = "diasUteis".equals(chave) ? "de segunda a sexta" : "no fim de semana";
            out.add(sinal("HORARIO", fracas.size() >= 3 ? "ALTO" : "MEDIO",
                    "Promoção relâmpago " + periodo + " nas horas fracas",
                    "Nas faixas " + faixaTexto + " " + periodo + " saíram " + pedidosFracos
                            + " pedidos, contra média de " + Math.round(media) + " por hora nas horas abertas. "
                            + "A loja está aberta e pagando custo fixo nessas horas.",
                    "Em Promoções, crie um cupom válido só nessa faixa e avise no status do WhatsApp.",
                    "promocoes.html", "Criar promoção do horário"));
        }
    }

    /** Loja cancelando mais que o teto e mais que as irmãs: é operação, não acaso. */
    @SuppressWarnings("unchecked")
    private void cancelamentoPorLoja(Map<String, Object> tempos, List<Map<String, Object>> out) {
        if (tempos == null) return;
        Map<String, Object> canc = (Map<String, Object>) tempos.get("cancelamentos");
        if (canc == null) return;
        List<Map<String, Object>> porLoja = (List<Map<String, Object>>) canc.get("porLoja");
        if (porLoja == null) return;
        for (Map<String, Object> l : porLoja) {
            BigDecimal pct = num(l.get("percentual"));
            int cancelados = num(l.get("cancelados")).intValue();
            if (cancelados < 5 || pct.compareTo(TETO_CANCELAMENTO_PCT) <= 0) continue; // pouco caso não é padrão
            out.add(sinal("CANCELAMENTO", "ALTO",
                    "Atacar o cancelamento da " + l.get("loja"),
                    l.get("loja") + " cancelou " + cancelados + " de " + num(l.get("pedidos")).intValue()
                            + " pedidos (" + pct + "%), acima do limite de " + TETO_CANCELAMENTO_PCT + "%.",
                    "Abra os pedidos cancelados dessa loja e fale com os clientes: o motivo repetido "
                            + "aponta a causa (tempo de preparo, falta de produto ou endereço).",
                    "pedidos.html", "Ver pedidos cancelados"));
        }
    }

    /** Um motivo que responde por boa parte dos cancelamentos é um problema só, com um conserto só. */
    @SuppressWarnings("unchecked")
    private void motivoDominante(Map<String, Object> tempos, List<Map<String, Object>> out) {
        if (tempos == null) return;
        Map<String, Object> canc = (Map<String, Object>) tempos.get("cancelamentos");
        if (canc == null) return;
        List<Map<String, Object>> motivos = (List<Map<String, Object>>) canc.get("porMotivo");
        int total = num(canc.get("total")).intValue();
        if (motivos == null || motivos.isEmpty() || total < 5) return;
        for (Map<String, Object> m : motivos) {
            int qtd = num(m.get("qtd")).intValue();
            if (qtd < total * FATIA_MOTIVO_DOMINANTE) continue;
            out.add(sinal("CANCELAMENTO", "ALTO",
                    "Resolver \"" + m.get("motivo") + "\", a causa que mais cancela",
                    qtd + " dos " + total + " cancelamentos do período foram por \"" + m.get("motivo")
                            + "\" — " + Math.round(qtd * 100.0 / total) + "% do total.",
                    "É um problema só, com um conserto só. Trate a causa antes de mexer em preço ou promoção.",
                    "pedidos.html", "Ver os cancelamentos"));
        }
    }

    /** Estoque que não chega até a próxima compra. */
    private void ruptura(List<Map<String, Object>> estoque, List<Map<String, Object>> out) {
        if (estoque == null) return;
        for (Map<String, Object> e : estoque) {
            BigDecimal dias = num(e.get("diasDeCobertura"));
            if (dias.compareTo(DIAS_RUPTURA) > 0) continue;
            out.add(sinal("ESTOQUE", dias.compareTo(BigDecimal.ONE) <= 0 ? "ALTO" : "MEDIO",
                    "Repor " + e.get("produto") + " na " + e.get("loja"),
                    "Restam " + e.get("estoque") + " unidades e a média é " + e.get("mediaPorDia")
                            + " por dia: " + dias + " dias de cobertura.",
                    "Lance a reposição em Estoque antes de acabar — produto em falta vira cancelamento.",
                    "estoque.html", "Abrir estoque"));
        }
    }

    /** Marketplace levando fatia grande demais do que entra. */
    @SuppressWarnings("unchecked")
    private void canalCaro(Map<String, Object> canais, List<Map<String, Object>> out) {
        if (canais == null) return;
        List<Map<String, Object>> lista = (List<Map<String, Object>>) canais.get("canais");
        if (lista == null) return;
        for (Map<String, Object> c : lista) {
            if (!Boolean.TRUE.equals(c.get("marketplace"))) continue;
            BigDecimal fat = num(c.get("faturamento"));
            BigDecimal comissao = num(c.get("comissaoEstimada"));
            if (fat.signum() == 0) continue;
            BigDecimal pct = comissao.multiply(BigDecimal.valueOf(100)).divide(fat, 1, RoundingMode.HALF_UP);
            if (pct.compareTo(TETO_COMISSAO_PCT) <= 0) continue;
            out.add(sinal("CANAL", "MEDIO",
                    "Puxar clientes do " + c.get("canal") + " para o canal próprio",
                    c.get("canal") + " faturou " + money(fat) + " e levou " + money(comissao)
                            + " de comissão (" + pct + "%).",
                    "Coloque o QR do cardápio próprio na embalagem e ofereça cashback só nele: "
                            + "o mesmo pedido pelo canal próprio não paga comissão.",
                    "cardapio-qr.html", "Ver cardápio próprio"));
        }
    }

    /** Etapa em que o pedido fica parado tempo demais. */
    @SuppressWarnings("unchecked")
    private void gargalo(Map<String, Object> tempos, List<Map<String, Object>> out) {
        if (tempos == null) return;
        List<Map<String, Object>> lista = (List<Map<String, Object>>) tempos.get("tempos");
        if (lista == null) return;
        for (Map<String, Object> t : lista) {
            BigDecimal min = num(t.get("minutosMedio"));
            if (min.compareTo(TETO_MINUTOS_ETAPA) <= 0 || num(t.get("amostras")).intValue() < 10) continue;
            out.add(sinal("LOJA", "MEDIO",
                    "Pedido parado em " + t.get("status"),
                    "Em média o pedido fica " + min + " minutos em " + t.get("status")
                            + " (" + t.get("amostras") + " pedidos medidos), acima do limite de "
                            + TETO_MINUTOS_ETAPA + " minutos.",
                    "Espera nessa etapa vira cancelamento e nota baixa. Veja o KDS no horário de pico.",
                    "kds.html", "Abrir KDS"));
        }
    }

    /** Produto ativo que quase não sai. */
    @SuppressWarnings("unchecked")
    private void produtoEncalhado(Map<String, Object> canais, List<Map<String, Object>> out) {
        if (canais == null) return;
        List<Map<String, Object>> menos = (List<Map<String, Object>>) canais.get("produtosMenos");
        if (menos == null) return;
        for (Map<String, Object> p : menos) {
            int qtd = num(p.get("quantidade")).intValue();
            if (qtd > VENDA_MINIMA_PRODUTO) continue;
            out.add(sinal("PRODUTO", "BAIXO",
                    "Rever " + p.get("produto"),
                    qtd + " venda(s) no período — o pior do cardápio.",
                    "Em Produtos, teste preço menor ou foto melhor por duas semanas; sem reação, tire da vitrine.",
                    "produtos.html", "Abrir produtos"));
        }
    }

    /** Loja vendendo com ticket bem abaixo das irmãs. */
    @SuppressWarnings("unchecked")
    private void lojaComTicketBaixo(Map<String, Object> balancete, List<Map<String, Object>> out) {
        if (balancete == null) return;
        List<Map<String, Object>> lojas = (List<Map<String, Object>>) balancete.get("lojas");
        Map<String, Object> total = (Map<String, Object>) balancete.get("total");
        if (lojas == null || lojas.size() < 2 || total == null) return;
        BigDecimal ticketRede = num(total.get("ticketMedio"));
        if (ticketRede.signum() == 0) return;
        for (Map<String, Object> l : lojas) {
            BigDecimal t = num(l.get("ticketMedio"));
            if (num(l.get("pedidos")).intValue() < 20) continue; // amostra curta engana
            if (t.doubleValue() >= ticketRede.doubleValue() * PISO_TICKET_RELATIVO) continue;
            out.add(sinal("LOJA", "MEDIO",
                    "Ticket da " + l.get("loja") + " abaixo da rede",
                    l.get("loja") + " vende a " + money(t) + " por pedido contra " + money(ticketRede)
                            + " da rede — " + Math.round((1 - t.doubleValue() / ticketRede.doubleValue()) * 100)
                            + "% menos.",
                    "Compare o cardápio e os adicionais dessa unidade com a de melhor ticket em Relatórios.",
                    "relatorios.html", "Comparar lojas"));
        }
    }
}
