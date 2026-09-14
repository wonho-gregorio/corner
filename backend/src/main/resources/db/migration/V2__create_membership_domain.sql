create table membership_products (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    name text not null check (btrim(name) <> ''),
    product_type text not null check (product_type in ('PERIOD', 'COUNT', 'HYBRID')),
    duration_value integer,
    duration_unit text,
    validity_value integer,
    validity_unit text,
    total_count integer,
    list_price_won bigint not null check (list_price_won >= 0),
    partial_payment_allowed boolean not null default false,
    default_payment_plan text not null default 'LUMP_SUM'
        check (default_payment_plan in ('LUMP_SUM', 'INSTALLMENT', 'SELECT_AT_ISSUE')),
    max_installment_count integer,
    minimum_initial_payment_type text,
    minimum_initial_payment_value bigint,
    use_before_full_payment_allowed boolean not null default false,
    overdue_attendance_policy text not null default 'ALLOW'
        check (overdue_attendance_policy in ('ALLOW', 'WARN', 'RESTRICT')),
    pause_allowed boolean not null default false,
    max_pause_count integer,
    max_pause_days_per_pause integer,
    max_total_pause_days integer,
    minimum_use_days_before_pause integer,
    extend_expiry_on_pause boolean not null default false,
    sale_status text not null default 'ON_SALE' check (sale_status in ('ON_SALE', 'STOPPED')),
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    constraint membership_products_type_values_ck check (
        (product_type = 'PERIOD'
            and duration_value is not null
            and duration_value > 0
            and duration_unit is not null
            and duration_unit in ('DAY', 'MONTH')
            and validity_value is null
            and validity_unit is null
            and total_count is null)
        or
        (product_type = 'COUNT'
            and duration_value is null
            and duration_unit is null
            and validity_value is not null
            and validity_value > 0
            and validity_unit is not null
            and validity_unit in ('DAY', 'MONTH')
            and total_count is not null
            and total_count > 0)
        or
        (product_type = 'HYBRID'
            and duration_value is not null
            and duration_value > 0
            and duration_unit is not null
            and duration_unit in ('DAY', 'MONTH')
            and validity_value is null
            and validity_unit is null
            and total_count is not null
            and total_count > 0)
    ),
    constraint membership_products_payment_policy_ck check (
        (not partial_payment_allowed
            and default_payment_plan = 'LUMP_SUM'
            and max_installment_count is null
            and minimum_initial_payment_type is null
            and minimum_initial_payment_value is null
            and not use_before_full_payment_allowed)
        or
        (partial_payment_allowed
            and max_installment_count is not null
            and max_installment_count >= 2
            and minimum_initial_payment_type is not null
            and minimum_initial_payment_type in ('AMOUNT', 'RATE')
            and minimum_initial_payment_value is not null
            and minimum_initial_payment_value > 0
            and (
                (minimum_initial_payment_type = 'AMOUNT' and minimum_initial_payment_value <= list_price_won)
                or
                (minimum_initial_payment_type = 'RATE' and minimum_initial_payment_value <= 100)
            ))
    ),
    constraint membership_products_pause_policy_ck check (
        (not pause_allowed
            and max_pause_count is null
            and max_pause_days_per_pause is null
            and max_total_pause_days is null
            and minimum_use_days_before_pause is null
            and not extend_expiry_on_pause)
        or
        (pause_allowed
            and max_pause_count is not null
            and max_pause_count > 0
            and max_pause_days_per_pause is not null
            and max_pause_days_per_pause > 0
            and max_total_pause_days is not null
            and max_total_pause_days > 0
            and max_pause_days_per_pause <= max_total_pause_days
            and minimum_use_days_before_pause is not null
            and minimum_use_days_before_pause >= 0)
    )
);

create unique index membership_products_gym_name_uidx on membership_products (gym_id, lower(name));
create index membership_products_gym_sale_idx on membership_products (gym_id, sale_status, product_type, id);
create index membership_products_created_by_idx on membership_products (created_by);
create index membership_products_updated_by_idx on membership_products (updated_by);

create table promotions (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    name text not null check (btrim(name) <> ''),
    starts_on date not null,
    ends_on date not null,
    discount_type text not null check (discount_type in ('RATE', 'AMOUNT')),
    discount_value bigint not null,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    admin_memo text,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    check (starts_on <= ends_on),
    check ((discount_type = 'RATE' and discount_value between 1 and 100)
        or (discount_type = 'AMOUNT' and discount_value > 0))
);

create unique index promotions_gym_name_uidx on promotions (gym_id, lower(name));
create index promotions_gym_period_idx on promotions (gym_id, status, starts_on, ends_on, id);
create index promotions_created_by_idx on promotions (created_by);
create index promotions_updated_by_idx on promotions (updated_by);

create table promotion_products (
    promotion_id bigint not null references promotions(id) on delete restrict,
    product_id bigint not null references membership_products(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    primary key (promotion_id, product_id)
);

create index promotion_products_product_idx on promotion_products (product_id, promotion_id);
create index promotion_products_created_by_idx on promotion_products (created_by);

create table memberships (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    member_id bigint not null references members(id) on delete restrict,
    product_id bigint not null references membership_products(id) on delete restrict,
    promotion_id bigint references promotions(id) on delete restrict,
    product_type text not null check (product_type in ('PERIOD', 'COUNT', 'HYBRID')),
    status text not null default 'SCHEDULED'
        check (status in ('SCHEDULED', 'ACTIVE', 'PAUSED', 'EXPIRED', 'EXHAUSTED', 'TERMINATED')),
    start_date date not null,
    end_date date not null,
    total_count integer,
    remaining_count integer,
    list_price_won bigint not null check (list_price_won >= 0),
    discount_won bigint not null default 0 check (discount_won >= 0),
    contract_amount_won bigint not null check (contract_amount_won >= 0),
    terms_snapshot jsonb not null,
    issued_at timestamptz not null default current_timestamp,
    issued_by bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    check (start_date <= end_date),
    check (contract_amount_won = list_price_won - discount_won),
    check ((product_type = 'PERIOD' and total_count is null and remaining_count is null)
        or (product_type in ('COUNT', 'HYBRID')
            and total_count is not null
            and total_count > 0
            and remaining_count is not null
            and remaining_count between 0 and total_count)),
    check (status <> 'EXHAUSTED' or (remaining_count is not null and remaining_count = 0))
);

create index memberships_member_status_end_idx on memberships (member_id, status, end_date, id);
create index memberships_gym_idx on memberships (gym_id, id);
create index memberships_gym_active_end_idx on memberships (gym_id, end_date, id) where status = 'ACTIVE';
create index memberships_gym_active_remaining_idx on memberships (gym_id, remaining_count, id)
    where status = 'ACTIVE' and remaining_count is not null;
create index memberships_product_idx on memberships (product_id, issued_at desc, id desc);
create index memberships_promotion_idx on memberships (promotion_id) where promotion_id is not null;
create index memberships_issued_by_idx on memberships (issued_by);
create index memberships_updated_by_idx on memberships (updated_by);

create table membership_pauses (
    id bigint generated always as identity primary key,
    membership_id bigint not null references memberships(id) on delete restrict,
    planned_start_date date not null,
    planned_end_date date not null,
    actual_resumed_on date,
    status text not null default 'PLANNED' check (status in ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    extension_days integer not null default 0 check (extension_days >= 0),
    is_policy_exception boolean not null default false,
    reason text,
    approved_by bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    check (planned_start_date <= planned_end_date),
    check (actual_resumed_on is null or actual_resumed_on >= planned_start_date),
    check ((status = 'COMPLETED' and actual_resumed_on is not null)
        or (status <> 'COMPLETED')),
    check (reason is null or btrim(reason) <> ''),
    check (not is_policy_exception or reason is not null)
);

create index membership_pauses_membership_idx on membership_pauses (membership_id, planned_start_date desc, id desc);
create unique index membership_pauses_one_current_uidx on membership_pauses (membership_id)
    where status in ('PLANNED', 'ACTIVE');
create index membership_pauses_approved_by_idx on membership_pauses (approved_by);

create table membership_events (
    id bigint generated always as identity primary key,
    membership_id bigint not null references memberships(id) on delete restrict,
    event_type text not null
        check (event_type in ('ISSUED', 'START_DATE_CHANGED', 'EXTENDED', 'PAUSED', 'RESUMED',
            'EXPIRED', 'EXHAUSTED', 'TERMINATED', 'STATUS_CHANGED')),
    from_status text,
    to_status text,
    old_start_date date,
    new_start_date date,
    old_end_date date,
    new_end_date date,
    effective_at timestamptz not null default current_timestamp,
    reason text,
    actor_account_id bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    check (from_status is null or from_status in ('SCHEDULED', 'ACTIVE', 'PAUSED', 'EXPIRED', 'EXHAUSTED', 'TERMINATED')),
    check (to_status is null or to_status in ('SCHEDULED', 'ACTIVE', 'PAUSED', 'EXPIRED', 'EXHAUSTED', 'TERMINATED')),
    check (reason is null or btrim(reason) <> '')
);

create index membership_events_membership_idx on membership_events (membership_id, effective_at desc, id desc);
create index membership_events_actor_idx on membership_events (actor_account_id);

create table membership_count_entries (
    id bigint generated always as identity primary key,
    membership_id bigint not null references memberships(id) on delete restrict,
    entry_type text not null
        check (entry_type in ('ISSUE', 'ATTENDANCE_DEBIT', 'ATTENDANCE_RESTORE', 'MANUAL_ADD', 'MANUAL_DEDUCT')),
    delta_count integer not null check (delta_count <> 0),
    source_type text not null check (source_type in ('ISSUE', 'ATTENDANCE', 'MANUAL')),
    source_id text,
    reverses_entry_id bigint references membership_count_entries(id) on delete restrict,
    reason text,
    actor_account_id bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    check ((entry_type in ('ISSUE', 'ATTENDANCE_RESTORE', 'MANUAL_ADD') and delta_count > 0)
        or (entry_type in ('ATTENDANCE_DEBIT', 'MANUAL_DEDUCT') and delta_count < 0)),
    check ((entry_type = 'ISSUE' and source_type = 'ISSUE')
        or (entry_type in ('ATTENDANCE_DEBIT', 'ATTENDANCE_RESTORE') and source_type = 'ATTENDANCE')
        or (entry_type in ('MANUAL_ADD', 'MANUAL_DEDUCT') and source_type = 'MANUAL')),
    check ((source_type = 'ATTENDANCE' and source_id is not null and btrim(source_id) <> '')
        or source_type <> 'ATTENDANCE'),
    check ((entry_type = 'ATTENDANCE_RESTORE' and reverses_entry_id is not null)
        or entry_type <> 'ATTENDANCE_RESTORE'),
    check (reverses_entry_id is null or reverses_entry_id <> id),
    check (reason is null or btrim(reason) <> ''),
    check (entry_type not in ('ATTENDANCE_RESTORE', 'MANUAL_ADD', 'MANUAL_DEDUCT') or reason is not null)
);

create index membership_count_entries_membership_idx
    on membership_count_entries (membership_id, created_at desc, id desc);
create index membership_count_entries_reverses_idx on membership_count_entries (reverses_entry_id)
    where reverses_entry_id is not null;
create index membership_count_entries_actor_idx on membership_count_entries (actor_account_id);
create unique index membership_count_entries_attendance_uidx
    on membership_count_entries (source_type, source_id, entry_type)
    where source_type = 'ATTENDANCE';
