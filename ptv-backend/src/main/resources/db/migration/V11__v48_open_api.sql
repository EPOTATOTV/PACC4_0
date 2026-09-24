-- v4.8 开放 API 平台：API 密钥（HMAC 鉴权）与调用审计
create table t_api_key (
    key_id             varchar(64)   not null primary key,
    name               varchar(128)  not null,
    tenant_id          varchar(64)   not null default 'platform',
    plan               varchar(16)   not null default 'PRO',
    secret_enc         longtext      not null,
    scopes             varchar(64)   not null default 'READ',
    categories         varchar(255)  null,
    ip_whitelist       varchar(255)  null,
    rate_limit_per_hour int          not null default 1000,
    webhook_url        varchar(512)  null,
    webhook_secret     varchar(255)  null,
    enabled            boolean       not null default true,
    created_at         timestamp(6)  not null,
    last_used_at       timestamp(6)  null,
    created_by         varchar(64)   null
);

create index idx_api_key_tenant on t_api_key (tenant_id, enabled);

create table t_api_usage_log (
    id             varchar(64)  not null primary key,
    api_key_id     varchar(64)  not null,
    tenant_id      varchar(64)  not null default 'platform',
    method         varchar(8)   not null,
    path           varchar(255) not null,
    ip             varchar(64)  null,
    status_code    int          not null,
    latency_ms     bigint       null,
    created_at     timestamp(6) not null
);

create index idx_api_usage_key_time on t_api_usage_log (api_key_id, created_at);
create index idx_api_usage_tenant on t_api_usage_log (tenant_id, created_at);