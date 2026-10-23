-- Por que esta loja esta com o acesso aberto.
--
-- `acesso_ate` nulo quer dizer "sem data de fim", e sozinho nao distingue dois casos que pedem
-- tratamentos OPOSTOS quando o Asaas avisa que a assinatura acabou:
--
--   ASSINATURA        quem paga pelo Asaas. Cancelou, ganha prazo e depois para -- senao usaria
--                     de graca para sempre, porque o bloqueio so olha a data.
--   PAGAMENTO_MANUAL  pagou fora do Asaas. O cancelamento la NAO pode encurtar nada: entrou dinheiro.
--
-- Foi essa confusao que tirou a Zira do ar em 15/10/2026. Ela pagou R$ 398, o registro manual
-- deixou o acesso sem data de fim, e o SUBSCRIPTION_DELETED seguinte gravou "hoje + carencia" por
-- cima. A guarda posta na v139 compara datas, e nulo nao entra em comparacao -- o acidente seguia
-- possivel. Ver MotivoDoAcesso.java.
alter table loja add column if not exists acesso_motivo varchar(30);

-- Linhas que ja existem. Nao da para adivinhar o passado, entao preenche so o que e seguro deduzir
-- do estado de AGORA, e o resto fica nulo (o codigo trata nulo como "regra antiga": ganha prazo).
--
--   acesso_ate nulo + assinatura ATIVA  -> esta pagando pelo Asaas
--   acesso_ate no futuro                -> alguem deu prazo; sem historico, a leitura honesta e
--                                          CORTESIA, que nao muda comportamento nenhum
-- 1) Quem pagou FORA do Asaas. E o caso que motivou tudo isto, e da para reconhecer: o registro
--    manual grava a chave com prefixo 'manual:'. So conta se for o pagamento MAIS RECENTE da loja --
--    um manual antigo, seguido de cobranca normal do Asaas, nao muda nada.
update loja l
   set acesso_motivo = 'PAGAMENTO_MANUAL'
 where l.acesso_motivo is null
   and exists (
         select 1
           from pagamento_assinatura p
          where p.loja_id = l.id
            and p.asaas_payment_id like 'manual:%'
            -- "o mais recente" por DATA, nao por linha: a Zira pagou R$ 398 em 15/10 e as duas
            -- metades entraram no mesmo dia -- uma manual e uma pelo Asaas. Pegando uma linha so,
            -- o desempate era sorte e a metade do Asaas ganhava.
            and p.pago_em = (select max(p2.pago_em)
                               from pagamento_assinatura p2
                              where p2.loja_id = l.id));

-- 2) Acesso sem data de fim com assinatura viva no Asaas: e o Asaas quem manda no prazo.
update loja l
   set acesso_motivo = 'ASSINATURA'
 where l.acesso_motivo is null
   and l.acesso_ate is null
   and exists (select 1 from assinatura a where a.loja_id = l.id and a.status = 'ATIVA');

-- 3) Tem prazo no futuro e nada acima explicou: alguem deu dias. Sem historico, a leitura honesta e
--    CORTESIA -- que e o comportamento de antes, nao muda nada.
update loja
   set acesso_motivo = 'CORTESIA'
 where acesso_motivo is null
   and acesso_ate is not null
   and acesso_ate > now();

-- O resto fica NULO de proposito: nao da para adivinhar, e nulo quer dizer "regra antiga".
-- ATENCAO: esta migracao NAO conserta data nenhuma. Loja que ja foi encurtada pelo defeito continua
-- com a data que ficou -- consertar isso e decisao de quem cobra, pela tela de liberar acesso.

comment on column loja.acesso_motivo is
  'Por que o acesso esta aberto: ASSINATURA, PAGAMENTO_MANUAL, LIBERACAO, CORTESIA, INADIMPLENCIA. '
  'Nulo = linha anterior a V60; o codigo trata como a regra antiga.';
