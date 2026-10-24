-- O recado do marketplace para de ser apagado pelo polling.
--
-- O DEFEITO (achado pelo QA em 24/10/2026)
-- Quando o marketplace RECUSA um status que o lojista avancou no painel, o
-- `avisarStatusNaoPropagado` grava a explicacao em `integracao_canal.ultimo_erro`, de proposito SEM
-- marcar a integracao como ERRO -- ela esta funcionando, foi o aviso que nao passou.
--
-- Mas `marcarPollingOk` faz `ultimo_erro = null` sem condicao nenhuma, e o poller roda a cada 30
-- segundos. Ou seja: o lojista tinha ATE 30 SEGUNDOS para ver o recado, e so se estivesse com a tela
-- de Integracoes aberta na hora. Na pratica, nunca via.
--
-- E o recado importa justamente porque o estrago continua: na 99 a ordem dos status e obrigatoria,
-- entao depois de um status recusado os seguintes DO MESMO PEDIDO tambem sao recusados. O lojista
-- avanca o pedido no painel, ve tudo verde do lado dele, e lá dentro o pedido ficou parado -- sem
-- nada na tela dizendo por que.
--
-- POR QUE COLUNA NOVA, E NAO UM CONSERTO NO UPDATE
-- `ultimo_erro` fazia dois trabalhos com significados opostos:
--   1. SAUDE da integracao (falha de polling, de autenticacao) -- tem de ser apagada quando volta a
--      funcionar, senao a tela mostra erro de uma integracao que esta boa;
--   2. RECADO sobre um pedido especifico -- NAO pode ser apagado por nada ter dado errado depois: o
--      pedido continua parado lá dentro.
--
-- Nao da para separar os dois pelo conteudo da coluna, e tentar adivinhar pelo texto seria guardar
-- semantica dentro de uma string. Tambem nao da para condicionar a limpeza ao status = 'ERRO':
-- `marcarPollingFalhou` grava erro SEM mexer no status, e esse caso tem de continuar sendo limpo.
--
-- A hora vem junto porque recado sem data nao da para usar: "a 99 nao aceitou o status do pedido
-- 1234" e uma informacao diferente se foi agora ou se foi semana passada.
alter table integracao_canal
    add column if not exists ultimo_aviso     varchar(500),
    add column if not exists ultimo_aviso_em  timestamp with time zone;

comment on column integracao_canal.ultimo_aviso is
    'Recado para o lojista sobre um pedido (ex.: o marketplace recusou um status). NAO e saude da '
    'integracao: o polling bem-sucedido NAO apaga esta coluna, so a ultimo_erro.';

comment on column integracao_canal.ultimo_aviso_em is
    'Quando o recado foi gravado. Sem a hora o lojista nao sabe se o recado e de agora ou de ontem.';

-- Os recados que estao na coluna velha neste instante nao sao recuperaveis: quem foi apagado pelo
-- polling, foi. Nao ha backfill possivel e nao faz sentido copiar `ultimo_erro` para ca -- o que
-- esta lá agora e, por construcao, saude de integracao (qualquer recado teria sido apagado em 30s).
