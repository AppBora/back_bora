package br.com.bora.dto;

public record NovaLojaRequest(
        String nomeLoja,
        String documento,
        String plano,
        String adminNome,
        String adminEmail,
        String adminSenha,
        /** Endereco da unidade. Opcional na criacao — quem cadastra em lote preenche depois. */
        EnderecoDaLoja endereco) {}
