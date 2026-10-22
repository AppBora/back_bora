package br.com.bora.service;

import br.com.bora.config.SegredoGuardado;
import br.com.bora.security.AuthContext;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cifra as credenciais que JÁ estavam gravadas em texto puro, depois que a chave foi ligada.
 *
 * <h2>Por que isto precisa existir (item 8.4, medido em 22/10/2026)</h2>
 * <p>O {@link SegredoGuardado} cifra <b>na escrita</b>. Ligar {@code BORA_SEGREDO_COLUNAS} protege o
 * que for gravado dali para frente e <b>não toca no que já está no banco</b> — e isso é de propósito,
 * para ligar a chave não exigir uma migração com segredo dentro. Mas a consequência é que, depois de
 * ligar, as credenciais antigas <b>continuam em texto puro</b> até alguém salvar aquela integração de
 * novo. E elas saem em todo {@code pg_dump}.</p>
 *
 * <p><b>Medido na produção em 22/10/2026</b>, com a chave ainda desligada: a chave Asaas de 1 loja, os
 * dois {@code client_secret} da plataforma (iFood e 99 — as credenciais que autorizam pedido em nome
 * de <i>todas</i> as lojas) e 6 segredos mais 3 tokens de integração, todos em texto puro.</p>
 *
 * <h2>Como funciona, e por que não usa JPA</h2>
 * <p>Pela via normal isto não funcionaria: ler e regravar o mesmo valor por JPA não gera UPDATE
 * nenhum — o Hibernate compara o valor em memória com o que carregou, vê que é igual e não escreve.
 * (Foi a primeira versão deste código, e estava errada.)</p>
 *
 * <p>Então aqui é SQL direto: lê o valor cru — que hoje está em texto puro —, cifra com o mesmo
 * conversor que o JPA usaria, e grava o resultado. A leitura seguinte pelo JPA decifra pelo prefixo,
 * como qualquer valor cifrado. É <b>idempotente</b>: o que já começa com {@code enc:v1:} não entra na
 * consulta.</p>
 *
 * <p><b>Nunca imprime, loga ou devolve valor nenhum</b> — só quantidades.</p>
 */
@Slf4j
@Service
public class CifragemEmRepousoService {

    private static final org.slf4j.Logger AUDITORIA =
            org.slf4j.LoggerFactory.getLogger(CifragemEmRepousoService.class);

    /** Tabela, coluna da chave primária e coluna do segredo. */
    private record Alvo(String tabela, String id, String coluna) {}

    /**
     * Toda coluna que passa pelo cifrador.
     *
     * <p>Quem puser {@code @Convert(SegredoGuardado)} numa coluna nova tem que acrescentar ela aqui,
     * senão a credencial antiga daquela coluna fica em texto puro para sempre. O
     * {@code CifragemCobreTodasAsColunasTest} lê as entidades e cobra isso.</p>
     */
    private static final List<Alvo> ALVOS = List.of(
            new Alvo("loja", "id", "asaas_api_key"),
            new Alvo("config_plataforma", "chave", "valor"),
            new Alvo("integracao_canal", "id", "client_secret"),
            new Alvo("integracao_canal", "id", "access_token"),
            new Alvo("integracao_canal", "id", "refresh_token"),
            new Alvo("integracao_canal", "id", "webhook_token"));

    private final EntityManager em;
    private final AuthContext ctx;

    public CifragemEmRepousoService(EntityManager em, AuthContext ctx) {
        this.em = em;
        this.ctx = ctx;
    }

    /** Quantas credenciais ainda estão em texto puro, por coluna. Não cifra nada. */
    public Map<String, Object> oQueFaltaCifrar() {
        ctx.requireAdminBora();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cifragemLigada", SegredoGuardado.ligado());
        Map<String, Object> porColuna = new LinkedHashMap<>();
        int total = 0;
        for (Alvo a : ALVOS) {
            int quantas = emTextoPuro(a).size();
            porColuna.put(a.tabela() + "." + a.coluna(), quantas);
            total += quantas;
        }
        out.put("emTextoPuro", porColuna);
        out.put("total", total);
        if (!SegredoGuardado.ligado() && total > 0) {
            out.put("aviso", "A cifragem esta DESLIGADA no servidor (BORA_SEGREDO_COLUNAS). Estas "
                    + total + " credencial(is) estao em texto puro no banco e em todo backup.");
        }
        return out;
    }

    /**
     * Cifra o que já está gravado. Exige a chave ligada.
     *
     * @return quantas credenciais foram cifradas, por coluna
     */
    @Transactional
    public Map<String, Object> cifrarOqueJaEstaGravado() {
        ctx.requireAdminBora();
        if (!SegredoGuardado.ligado()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A cifragem em repouso esta DESLIGADA (BORA_SEGREDO_COLUNAS nao definida no "
                            + "servidor). Ligue a chave primeiro: regravar sem ela nao protege nada.");
        }
        SegredoGuardado cifrador = new SegredoGuardado();
        Map<String, Object> porColuna = new LinkedHashMap<>();
        int total = 0;
        for (Alvo a : ALVOS) {
            int cifradas = 0;
            for (Object[] linha : emTextoPuro(a)) {
                Object id = linha[0];
                String claro = (String) linha[1];
                String cifrado = cifrador.convertToDatabaseColumn(claro);
                if (cifrado == null || cifrado.equals(claro)) continue; // nada mudou: nao grava
                em.createNativeQuery("update " + a.tabela() + " set " + a.coluna()
                                + " = ?1 where " + a.id() + " = ?2")
                        .setParameter(1, cifrado).setParameter(2, id).executeUpdate();
                cifradas++;
            }
            porColuna.put(a.tabela() + "." + a.coluna(), cifradas);
            total += cifradas;
        }
        AUDITORIA.warn("AUDITORIA seguranca: usuario {} cifrou {} credencial(is) que estavam em texto "
                        + "puro no banco. Detalhe por coluna: {}",
                ctx.atual() == null ? null : ctx.atual().userId(), total, porColuna);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cifradas", porColuna);
        out.put("total", total);
        return out;
    }

    /**
     * As credenciais em texto puro de uma coluna: {@code [id, valor]}.
     *
     * <p>O filtro {@code not like 'enc:v1:%'} é o que torna isto idempetente e barato: rodar de novo
     * não acha nada.</p>
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> emTextoPuro(Alvo a) {
        return em.createNativeQuery("select " + a.id() + ", " + a.coluna() + " from " + a.tabela()
                        + " where " + a.coluna() + " is not null and " + a.coluna() + " <> ''"
                        + " and " + a.coluna() + " not like 'enc:v1:%'")
                .getResultList();
    }
}
