alter table memberships add column idempotency_key text;

alter table memberships add constraint memberships_idempotency_key_ck
    check (idempotency_key is null or btrim(idempotency_key) <> '');

create unique index memberships_gym_idempotency_uidx
    on memberships (gym_id, idempotency_key)
    where idempotency_key is not null;
