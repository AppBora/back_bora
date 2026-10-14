package br.com.bora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Entrega a chave de cifra das colunas ao {@link SegredoGuardado} no start.
 *
 * <p>O conversor do Hibernate e instanciado por ele mesmo, fora do Spring — nao ha injecao la dentro.
 * Esta classe existe so para ligar as duas coisas, uma vez, antes de qualquer leitura de entidade.</p>
 *
 * <p>A chave vem de {@code BORA_SEGREDO_COLUNAS} e NAO tem valor padrao: sem ela, as credenciais
 * continuam em texto puro (com aviso no log), que e o comportamento de antes. Isto e deliberado — ver
 * o javadoc do {@link SegredoGuardado} sobre por que o valor padrao aqui nao pode ser um segredo
 * inventado.</p>
 *
 * <p>Gerar: {@code openssl rand -base64 48}. Guardar junto do {@code api.env} no gerenciador de
 * senhas: <b>perder a chave e perder as credenciais dos lojistas.</b></p>
 */
@Configuration
public class ChaveDasColunas {

    private final String segredo;

    public ChaveDasColunas(@Value("${bora.seguranca.segredo-colunas:}") String segredo) {
        this.segredo = segredo;
    }

    @PostConstruct
    void ligar() {
        SegredoGuardado.usarChave(segredo);
    }
}
