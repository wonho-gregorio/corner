create table charges (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    member_id bigint not null references members(id) on delete restrict,
    membership_id bigint not null unique references memberships(id) on delete restrict,
    plan_type text not null check (plan_type in ('LUMP_SUM', 'PARTIAL', 'INSTALLMENT', 'UNPAID')),
    contract_amount_won bigint not null check (contract_amount_won >= 0),
    adjusted_amount_won bigint not null check (adjusted_amount_won >= 0),
    paid_amount_won bigint not null default 0 check (paid_amount_won >= 0),
    balance_won bigint not null check (balance_won >= 0),
    status text not null default 'SCHEDULED'
        check (status in ('SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED')),
    first_due_on date not null,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    check (adjusted_amount_won = paid_amount_won + balance_won),
    check (adjusted_amount_won <= contract_amount_won),
    check ((status = 'SCHEDULED' and paid_amount_won = 0 and balance_won = adjusted_amount_won)
        or (status = 'PARTIALLY_PAID' and paid_amount_won > 0 and balance_won > 0)
        or (status = 'PAID' and balance_won = 0 and paid_amount_won = adjusted_amount_won)
        or (status = 'OVERDUE' and balance_won > 0)
        or (status = 'CANCELLED' and paid_amount_won = 0 and balance_won = 0 and adjusted_amount_won = 0))
);

create index charges_gym_status_due_idx on charges (gym_id, status, first_due_on, id);
create index charges_member_idx on charges (member_id, created_at desc, id desc);
create index charges_created_by_idx on charges (created_by);
create index charges_updated_by_idx on charges (updated_by);

create table charge_installments (
    id bigint generated always as identity primary key,
    charge_id bigint not null references charges(id) on delete restrict,
    installment_no integer not null check (installment_no > 0),
    due_on date not null,
    amount_won bigint not null check (amount_won >= 0),
    paid_amount_won bigint not null default 0 check (paid_amount_won >= 0 and paid_amount_won <= amount_won),
    status text not null default 'SCHEDULED'
        check (status in ('SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED')),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    unique (charge_id, installment_no),
    check ((status = 'SCHEDULED' and paid_amount_won = 0)
        or (status = 'PARTIALLY_PAID' and paid_amount_won > 0 and paid_amount_won < amount_won)
        or (status = 'PAID' and paid_amount_won = amount_won)
        or (status = 'OVERDUE' and paid_amount_won < amount_won)
        or (status = 'CANCELLED' and paid_amount_won = 0))
);

create index charge_installments_open_due_idx on charge_installments (status, due_on, id)
    where status in ('SCHEDULED', 'PARTIALLY_PAID', 'OVERDUE');

create table charge_plan_revisions (
    id bigint generated always as identity primary key,
    charge_id bigint not null references charges(id) on delete restrict,
    revision_no integer not null check (revision_no > 0),
    before_plan jsonb not null,
    after_plan jsonb not null,
    reason text not null check (btrim(reason) <> ''),
    changed_by bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    unique (charge_id, revision_no)
);

create index charge_plan_revisions_changed_by_idx on charge_plan_revisions (changed_by);

create table payment_operations (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    charge_id bigint not null references charges(id) on delete restrict,
    operation_type text not null check (operation_type in ('REGISTER', 'CANCEL', 'CORRECT', 'REFUND')),
    idempotency_key text not null check (btrim(idempotency_key) <> ''),
    processed_at timestamptz not null default current_timestamp,
    processed_by bigint not null references staff_accounts(id) on delete restrict,
    reason text,
    memo text,
    unique (gym_id, idempotency_key),
    check (reason is null or btrim(reason) <> ''),
    check (operation_type = 'REGISTER' or reason is not null)
);

create index payment_operations_charge_idx on payment_operations (charge_id, processed_at desc, id desc);
create index payment_operations_processed_by_idx on payment_operations (processed_by);

create table payment_transactions (
    id bigint generated always as identity primary key,
    operation_id bigint not null references payment_operations(id) on delete restrict,
    charge_id bigint not null references charges(id) on delete restrict,
    transaction_type text not null check (transaction_type in ('PAYMENT', 'CANCELLATION', 'REFUND')),
    payment_method text not null check (payment_method in ('CASH', 'CARD', 'BANK_TRANSFER')),
    amount_won bigint not null check (amount_won > 0),
    original_transaction_id bigint references payment_transactions(id) on delete restrict,
    refund_deduction_won bigint,
    used_days integer,
    used_count integer,
    input_source text not null default 'MANUAL' check (input_source in ('MANUAL', 'PROVIDER')),
    provider text,
    external_transaction_id text,
    approval_number text,
    sync_status text not null default 'NOT_APPLICABLE'
        check (sync_status in ('NOT_APPLICABLE', 'PENDING', 'SYNCED', 'FAILED')),
    created_at timestamptz not null default current_timestamp,
    check ((transaction_type = 'PAYMENT' and original_transaction_id is null)
        or (transaction_type in ('CANCELLATION', 'REFUND') and original_transaction_id is not null)),
    check (original_transaction_id is null or original_transaction_id <> id),
    check ((transaction_type = 'REFUND'
            and refund_deduction_won is not null
            and refund_deduction_won >= 0
            and used_days is not null
            and used_days >= 0
            and used_count is not null
            and used_count >= 0)
        or (transaction_type <> 'REFUND'
            and refund_deduction_won is null
            and used_days is null
            and used_count is null)),
    check ((input_source = 'MANUAL' and provider is null and external_transaction_id is null)
        or (input_source = 'PROVIDER' and provider is not null and btrim(provider) <> '')),
    check ((input_source = 'MANUAL' and sync_status = 'NOT_APPLICABLE')
        or input_source = 'PROVIDER'),
    check (external_transaction_id is null or btrim(external_transaction_id) <> ''),
    check (approval_number is null or btrim(approval_number) <> '')
);

create index payment_transactions_operation_idx on payment_transactions (operation_id, id);
create index payment_transactions_charge_idx on payment_transactions (charge_id, created_at desc, id desc);
create index payment_transactions_original_idx on payment_transactions (original_transaction_id)
    where original_transaction_id is not null;
create unique index payment_transactions_provider_external_uidx
    on payment_transactions (provider, external_transaction_id)
    where provider is not null and external_transaction_id is not null;

create table membership_terminations (
    id bigint generated always as identity primary key,
    membership_id bigint not null unique references memberships(id) on delete restrict,
    refund_operation_id bigint unique references payment_operations(id) on delete restrict,
    effective_date date not null,
    stop_mode text not null check (stop_mode in ('IMMEDIATE', 'EFFECTIVE_DATE_START', 'EFFECTIVE_DATE_END')),
    stop_at timestamptz not null,
    reason text not null check (btrim(reason) <> ''),
    processed_by bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp
);

create index membership_terminations_processed_by_idx on membership_terminations (processed_by);
