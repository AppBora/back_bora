package br.com.bora.dto;

/** Cadastro self-service de uma nova loja (público). O plano é o único: R$ 299/mês por loja. */
public record SignupRequest(
        String nomeLoja,
        String documento,
        String adminNome,
        String adminEmail,
        String adminSenha) {}
