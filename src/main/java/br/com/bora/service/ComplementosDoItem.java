package br.com.bora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Como os complementos escolhidos ficam guardados no item do pedido, e como voltam.
 *
 * <h2>Por que guarda nome e preço, e não só o id</h2>
 * <p>{@code ComplementoController.salvar} apaga e recria todos os grupos e itens do produto a cada
 * gravação, com ids novos da sequência. Guardar só o id faria o histórico apontar para o nada na
 * primeira vez que o lojista corrigisse um preço — e o "repetir pedido" devolveria um copo sem os
 * adicionais de sempre. Com o nome guardado, quem repete reencontra o adicional mesmo depois do
 * cadastro ser refeito.</p>
 *
 * <h2>Os três estados, que são diferentes de propósito</h2>
 * <ul>
 *   <li>{@code null} — item criado antes desta gravação existir. Não se sabe o que foi escolhido.</li>
 *   <li>{@code []} — item sem complemento nenhum, e isso é certeza, não desconhecimento.</li>
 *   <li>lista com itens — o que foi escolhido, com o nome e o preço daquele dia.</li>
 * </ul>
 * <p>A primeira versão gravava {@code null} nos dois primeiros casos. Um pedido antigo com
 * adicionais, num produto de grupo opcional, voltava como produto puro e sem aviso: o cliente
 * recebia um açaí diferente do que pediu e ninguém ficava sabendo.</p>
 *
 * <p>Escrever e ler moram aqui, e não nos dois pontos que criam pedido, para o formato não divergir
 * entre o cardápio e o painel.</p>
 */
@Slf4j
public final class ComplementosDoItem {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ComplementoService.Escolhido>> LISTA = new TypeReference<>() {};

    private ComplementosDoItem() {}

    /** O que gravar na coluna. Lista vazia vira {@code "[]"}, que não é a mesma coisa que nulo. */
    public static String escrever(List<ComplementoService.Escolhido> escolhidos) {
        try {
            return JSON.writeValueAsString(escolhidos == null ? List.of() : escolhidos);
        } catch (Exception e) {
            // Não pode derrubar a criação do pedido: sem o registro o repetir fica pior, mas a
            // venda acontece. Nulo aqui significa "não sei o que foi escolhido", que e a verdade.
            log.warn("não consegui guardar os complementos do item: {}", e.getMessage());
            return null;
        }
    }

    /** O que foi escolhido, ou {@code null} quando o item não registrou (item antigo). */
    public static List<ComplementoService.Escolhido> ler(String guardado) {
        if (guardado == null || guardado.isBlank()) return null;
        try {
            return JSON.readValue(guardado, LISTA);
        } catch (Exception e) {
            log.warn("registro de complementos ilegível, tratando como item antigo: {}", e.getMessage());
            return null;
        }
    }
}
