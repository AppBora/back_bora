package br.com.bora.service;

import br.com.bora.repository.ConfigPlataformaRepository;
import org.springframework.stereotype.Service;

/**
 * Módulo fiscal (NFC-e via API externa, ex.: Focus NFe).
 * DESLIGADO por padrão (decisão 2026-07-07): só será habilitado pelo ADMINISTRADOR_BORA
 * quando o faturamento da plataforma atingir R$ 5 mil/mês livre.
 * Toda emissão futura DEVE passar por {@link #habilitado()} antes de qualquer chamada externa.
 */
@Service
public class FiscalService {

    private final ConfigPlataformaRepository configs;

    public FiscalService(ConfigPlataformaRepository configs) {
        this.configs = configs;
    }

    /** true somente se o ADMINISTRADOR_BORA ligou o módulo fiscal na plataforma. */
    public boolean habilitado() {
        return configs.findById("fiscal.habilitado")
                .map(c -> Boolean.parseBoolean(c.getValor()))
                .orElse(false);
    }

    /** Lança 409 se o módulo estiver desligado — TODO ponto de emissão chama isto primeiro. */
    public void exigirHabilitado() {
        if (!habilitado()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Emissão fiscal indisponível no momento");
        }
    }

    /**
     * Emissão de NFC-e via Focus NFe — DORMENTE atrás da chave global.
     * Pré-requisitos quando for ligar: token Focus em config_plataforma ('fiscal.focus-token'),
     * CNPJ da loja em loja.documento e certificado digital A1 da loja cadastrado no painel Focus.
     */
    public java.util.Map<String, Object> emitirNfce(br.com.bora.entity.Loja loja,
                                                    br.com.bora.entity.Pedido pedido,
                                                    java.util.List<br.com.bora.entity.PedidoItem> itens) {
        exigirHabilitado();
        String token = configs.findById("fiscal.focus-token")
                .map(br.com.bora.entity.ConfigPlataforma::getValor)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Token da API fiscal não configurado"));
        if (loja.documento == null || loja.documento.replaceAll("\\D", "").length() != 14) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Loja sem CNPJ cadastrado para emissão");
        }
        java.util.Map<String, Object> nota = new java.util.LinkedHashMap<>();
        nota.put("cnpj_emitente", loja.documento.replaceAll("\\D", ""));
        nota.put("data_emissao", java.time.OffsetDateTime.now().toString());
        nota.put("presenca_comprador", 1);
        nota.put("modalidade_frete", 9);
        nota.put("formas_pagamento", java.util.List.of(java.util.Map.of(
                "forma_pagamento", pedido.formaPagamento != null && pedido.formaPagamento.startsWith("PIX") ? "17" : "01",
                "valor_pagamento", pedido.valorTotal)));
        java.util.List<java.util.Map<String, Object>> linhas = new java.util.ArrayList<>();
        int n = 1;
        for (br.com.bora.entity.PedidoItem it : itens) {
            java.util.Map<String, Object> li = new java.util.LinkedHashMap<>();
            li.put("numero_item", n++);
            li.put("codigo_produto", it.getProdutoId());
            li.put("descricao", it.getDescricao());
            li.put("quantidade_comercial", it.getQuantidade());
            li.put("valor_unitario_comercial", it.getPrecoUnitario());
            li.put("valor_bruto", it.getSubtotal());
            li.put("unidade_comercial", "un");
            li.put("codigo_ncm", "21069090"); // genérico alimentos preparados — revisar por produto ao ligar
            li.put("icms_situacao_tributaria", "102");
            li.put("icms_origem", 0);
            linhas.add(li);
        }
        nota.put("items", linhas);
        String ref = "borahapp-" + loja.id + "-" + pedido.id;
        return org.springframework.web.client.RestClient.create().post()
                .uri("https://api.focusnfe.com.br/v2/nfce?ref=" + ref)
                .headers(h -> h.setBasicAuth(token, ""))
                .body(nota)
                .retrieve().body(java.util.Map.class);
    }
}
