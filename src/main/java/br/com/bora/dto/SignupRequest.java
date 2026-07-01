package br.com.bora.dto;

/** Cadastro self-service de uma nova loja (público). O plano inicial é sempre START. */
public record SignupRequest(
        String nomeLoja,
        String documento,
        String adminNome,
        String adminEmail,
        String adminSenha) {}
