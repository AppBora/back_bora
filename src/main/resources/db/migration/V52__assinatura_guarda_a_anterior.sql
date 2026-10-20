-- A assinatura guarda o id da que ela substituiu.
--
-- POR QUE: o `assinar` cancelava a assinatura no Asaas e SO DEPOIS criava a nova, dentro de uma
-- transacao. Se a criacao falhasse, o nosso banco fazia rollback e voltava a apontar para a
-- assinatura antiga -- que LA FORA ja estava morta. O painel dizia que estava tudo certo e a loja
-- ficava sem cobranca nenhuma, calado. E o estado exato em que a loja da Zira esta.
--
-- O conserto inverte a ordem: CRIA primeiro, cancela depois. Se a criacao falhar, nada foi cancelado
-- e o estado continua consistente. Mas isso abre um risco novo e menor: se o CANCELAMENTO falhar,
-- sobram duas assinaturas vivas e o lojista e cobrado em dobro.
--
-- Entre os dois riscos, dobro e melhor que zero: cobrar duas vezes e visivel, o lojista reclama e da
-- para estornar; nao cobrar nenhuma vez e invisivel e a receita some. Mas "melhor" nao e "aceitavel",
-- e e para isso que esta coluna existe: o id da assinatura que ficou orfa fica GRAVADO, em vez de
-- viver so numa linha de log que rotaciona. Sem ela, descobrir qual cobranca cancelar a mao exigiria
-- procurar no Asaas qual das duas e a velha.
--
-- Fica nula no caminho normal. Preenchida = ha uma assinatura antiga que talvez precise ser
-- cancelada a mao no Asaas; o log de ERRO do mesmo momento diz se o cancelamento falhou.

set lock_timeout = '5s';

alter table assinatura add column if not exists asaas_subscription_anterior varchar(60);

comment on column assinatura.asaas_subscription_anterior is
  'Id da assinatura do Asaas que esta substituiu. Preenchida quando o cancelamento da antiga falhou '
  'depois de a nova ter sido criada: ha cobranca em duplicidade para cancelar a mao. Ver '
  'AssinaturaService.assinar.';
