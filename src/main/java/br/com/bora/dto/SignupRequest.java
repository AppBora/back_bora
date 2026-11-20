package br.com.bora.dto;

/** Cadastro self-service de uma nova loja (público). O plano é o único: R$ 199/mês por loja (PLANOS_SAAS.md). */
public record SignupRequest(
        String nomeLoja,
        String documento,
        String adminNome,
        String adminEmail,
        String adminSenha,
        /** Caixa "Li e aceito" marcada na tela de cadastro. */
        Boolean aceiteTermos) {}
