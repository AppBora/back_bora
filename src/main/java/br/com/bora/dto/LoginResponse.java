package br.com.bora.dto;

public record LoginResponse(String token, String nome, String papel, Long lojaId) {}
