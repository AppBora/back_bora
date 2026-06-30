package br.com.bora.dto;

/** Visão pública de usuário — nunca expõe o hash da senha. */
public record UsuarioView(Long id, String nome, String email, String papel, Boolean ativo, Long lojaId) {}
