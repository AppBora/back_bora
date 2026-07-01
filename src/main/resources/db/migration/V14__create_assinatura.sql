create table assinatura (
    id bigserial primary key,
    loja_id bigint not null,
    plano varchar(255) not null,
    status varchar(255) not null default 'PENDENTE',
    valor numeric(12,2),
    asaas_customer_id varchar(255),
    asaas_subscription_id varchar(255),
    criado_em timestamp with time zone not null default now(),
    atualizado_em timestamp with time zone not null default now()
);
create unique index ux_assinatura_loja on assinatura (loja_id);
create index ix_assinatura_asaas_sub on assinatura (asaas_subscription_id);
