alter table charges add column minimum_initial_payment_type text;
alter table charges add column minimum_initial_payment_value bigint;

alter table charges add constraint charges_minimum_initial_payment_ck check (
    (minimum_initial_payment_type is null and minimum_initial_payment_value is null)
    or
    (minimum_initial_payment_type in ('AMOUNT', 'RATE')
        and minimum_initial_payment_value is not null
        and minimum_initial_payment_value > 0
        and (minimum_initial_payment_type <> 'RATE' or minimum_initial_payment_value <= 100))
);

create table payment_installment_allocations (
    id bigint generated always as identity primary key,
    transaction_id bigint not null references payment_transactions(id) on delete restrict,
    installment_id bigint not null references charge_installments(id) on delete restrict,
    amount_won bigint not null check (amount_won > 0),
    created_at timestamptz not null default current_timestamp,
    unique (transaction_id, installment_id)
);

create index payment_installment_allocations_installment_idx
    on payment_installment_allocations (installment_id, transaction_id);
