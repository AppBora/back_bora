package br.com.bora.security;

/** Identidade autenticada extraída do JWT e disponível no contexto de segurança. */
public record BoraPrincipal(Long userId, Long lojaId, String papel, String email) {}
