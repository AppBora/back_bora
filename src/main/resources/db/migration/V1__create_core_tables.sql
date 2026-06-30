create table loja(id bigserial primary key,nome varchar(160),documento varchar(40),ativo boolean default true);
create table cliente(id bigserial primary key,loja_id bigint not null,nome varchar(160),telefone varchar(40),endereco varchar(255),bairro varchar(100),referencia varchar(255));
create table produto(id bigserial primary key,loja_id bigint not null,nome varchar(160),categoria varchar(80),preco numeric(12,2),ativo boolean default true);
create table configuracao_loja(id bigserial primary key,loja_id bigint not null,nome_exibicao varchar(160),nome_sistema varchar(160),logo_url text,cor_primaria varchar(20),cor_secundaria varchar(20),banner_url text,subdominio varchar(120),mostrar_marca_bora boolean default true);
