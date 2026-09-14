create table gyms (
    id bigint generated always as identity primary key,
    name text not null check (btrim(name) <> ''),
    representative_phone text,
    address text,
    address_detail text,
    member_number_format text not null default 'YYYYMM-####',
    checkout_enabled boolean not null default false,
    created_at timestamptz not null default current_timestamp,
    created_by bigint,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint,
    version bigint not null default 0
);

create table staff_accounts (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    login_id text not null check (btrim(login_id) <> ''),
    password_hash text not null check (btrim(password_hash) <> ''),
    name text not null check (btrim(name) <> ''),
    role text not null check (role in ('ADMIN', 'STAFF')),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    must_change_password boolean not null default true,
    session_version bigint not null default 0 check (session_version >= 0),
    last_login_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0
);

alter table gyms
    add constraint gyms_created_by_fk foreign key (created_by) references staff_accounts(id) on delete restrict,
    add constraint gyms_updated_by_fk foreign key (updated_by) references staff_accounts(id) on delete restrict;

create unique index staff_accounts_gym_login_uidx on staff_accounts (gym_id, lower(login_id));
create index staff_accounts_gym_status_idx on staff_accounts (gym_id, status);
create index staff_accounts_created_by_idx on staff_accounts (created_by);
create index staff_accounts_updated_by_idx on staff_accounts (updated_by);
create index gyms_created_by_idx on gyms (created_by);
create index gyms_updated_by_idx on gyms (updated_by);

create table staff_permissions (
    staff_account_id bigint not null references staff_accounts(id) on delete cascade,
    permission_code text not null check (permission_code in ('MEMBER_MANAGE', 'ATTENDANCE_PROCESS', 'PAYMENT_REGISTER')),
    granted_at timestamptz not null default current_timestamp,
    granted_by bigint references staff_accounts(id) on delete restrict,
    primary key (staff_account_id, permission_code)
);

create index staff_permissions_granted_by_idx on staff_permissions (granted_by);

create table auth_refresh_sessions (
    id bigint generated always as identity primary key,
    staff_account_id bigint not null references staff_accounts(id) on delete cascade,
    token_hash text not null unique check (btrim(token_hash) <> ''),
    session_version bigint not null check (session_version >= 0),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    created_ip text,
    user_agent text,
    check (expires_at > created_at),
    check (revoked_at is null or revoked_at >= created_at)
);

create index auth_refresh_sessions_staff_idx on auth_refresh_sessions (staff_account_id, expires_at desc);
create index auth_refresh_sessions_active_idx on auth_refresh_sessions (staff_account_id, expires_at)
    where revoked_at is null;

create table audit_logs (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    actor_account_id bigint references staff_accounts(id) on delete restrict,
    module text not null check (btrim(module) <> ''),
    action text not null check (btrim(action) <> ''),
    subject_type text not null check (btrim(subject_type) <> ''),
    subject_id text,
    reason text,
    before_values jsonb,
    after_values jsonb,
    occurred_at timestamptz not null default current_timestamp
);

create index audit_logs_gym_occurred_idx on audit_logs (gym_id, occurred_at desc, id desc);
create index audit_logs_actor_idx on audit_logs (actor_account_id, occurred_at desc);
create index audit_logs_subject_idx on audit_logs (gym_id, subject_type, subject_id, occurred_at desc);

create table outbox_events (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    aggregate_type text not null check (btrim(aggregate_type) <> ''),
    aggregate_id text not null check (btrim(aggregate_id) <> ''),
    event_type text not null check (btrim(event_type) <> ''),
    payload jsonb not null,
    occurred_at timestamptz not null default current_timestamp,
    published_at timestamptz,
    attempt_count integer not null default 0 check (attempt_count >= 0),
    last_error text,
    check (published_at is null or published_at >= occurred_at)
);

create index outbox_events_unpublished_idx on outbox_events (occurred_at, id) where published_at is null;
create index outbox_events_gym_aggregate_idx on outbox_events (gym_id, aggregate_type, aggregate_id);

create table member_number_sequences (
    gym_id bigint not null references gyms(id) on delete restrict,
    year_month text not null check (year_month ~ '^[0-9]{6}$'),
    last_value integer not null default 0 check (last_value >= 0),
    updated_at timestamptz not null default current_timestamp,
    primary key (gym_id, year_month)
);

create table member_groups (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    name text not null check (btrim(name) <> ''),
    display_order integer not null default 0 check (display_order >= 0),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0
);

create unique index member_groups_gym_name_uidx on member_groups (gym_id, lower(name));
create index member_groups_gym_order_idx on member_groups (gym_id, status, display_order, id);
create index member_groups_created_by_idx on member_groups (created_by);
create index member_groups_updated_by_idx on member_groups (updated_by);

create table members (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    member_group_id bigint references member_groups(id) on delete restrict,
    member_number text not null check (btrim(member_number) <> ''),
    status text not null default 'ACTIVE'
        check (status in ('ACTIVE', 'CONSULTING', 'EXPIRED', 'ARCHIVED', 'DELETION_REQUESTED', 'DELETED')),
    name text not null check (btrim(name) <> ''),
    phone text,
    normalized_phone text check (normalized_phone is null or normalized_phone ~ '^[0-9]+$'),
    birth_date date,
    gender text check (gender is null or gender in ('MALE', 'FEMALE', 'OTHER', 'UNSPECIFIED')),
    address text,
    address_detail text,
    emergency_contact_name text,
    emergency_contact_phone text,
    health_notes text,
    profile_image_key text,
    registered_on date not null default current_date,
    membership_expired_at timestamptz,
    archived_at timestamptz,
    deletion_requested_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    unique (gym_id, member_number)
);

create index members_gym_status_name_idx on members (gym_id, status, name, id);
create index members_gym_phone_idx on members (gym_id, normalized_phone) where normalized_phone is not null;
create index members_group_idx on members (member_group_id, status, name, id);
create index members_created_by_idx on members (created_by);
create index members_updated_by_idx on members (updated_by);

create table member_status_history (
    id bigint generated always as identity primary key,
    member_id bigint not null references members(id) on delete restrict,
    from_status text,
    to_status text not null
        check (to_status in ('ACTIVE', 'CONSULTING', 'EXPIRED', 'ARCHIVED', 'DELETION_REQUESTED', 'DELETED')),
    effective_at timestamptz not null default current_timestamp,
    reason text,
    changed_by bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    check (from_status is null or from_status in ('ACTIVE', 'CONSULTING', 'EXPIRED', 'ARCHIVED', 'DELETION_REQUESTED', 'DELETED'))
);

create index member_status_history_member_idx on member_status_history (member_id, effective_at desc, id desc);
create index member_status_history_changed_by_idx on member_status_history (changed_by);

create table member_group_history (
    id bigint generated always as identity primary key,
    member_id bigint not null references members(id) on delete restrict,
    from_group_id bigint references member_groups(id) on delete restrict,
    to_group_id bigint references member_groups(id) on delete restrict,
    effective_on date not null,
    reason text,
    changed_by bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    check (from_group_id is distinct from to_group_id)
);

create index member_group_history_member_idx on member_group_history (member_id, effective_on desc, id desc);
create index member_group_history_from_group_idx on member_group_history (from_group_id);
create index member_group_history_to_group_idx on member_group_history (to_group_id);
create index member_group_history_changed_by_idx on member_group_history (changed_by);

create table guardians (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    name text not null check (btrim(name) <> ''),
    phone text not null check (btrim(phone) <> ''),
    normalized_phone text not null check (normalized_phone ~ '^[0-9]+$'),
    email text,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0
);

create index guardians_gym_phone_idx on guardians (gym_id, normalized_phone);
create index guardians_created_by_idx on guardians (created_by);
create index guardians_updated_by_idx on guardians (updated_by);

create table member_guardians (
    member_id bigint not null references members(id) on delete restrict,
    guardian_id bigint not null references guardians(id) on delete restrict,
    relationship text not null check (relationship in ('PARENT', 'GRANDPARENT', 'SIBLING', 'SPOUSE', 'OTHER')),
    is_primary boolean not null default false,
    receives_payment_notice boolean not null default true,
    receives_lesson_notice boolean not null default true,
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    primary key (member_id, guardian_id)
);

create unique index member_guardians_one_primary_uidx on member_guardians (member_id) where is_primary;
create index member_guardians_guardian_idx on member_guardians (guardian_id, member_id);
create index member_guardians_created_by_idx on member_guardians (created_by);

create table member_relationships (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    lower_member_id bigint not null references members(id) on delete restrict,
    higher_member_id bigint not null references members(id) on delete restrict,
    lower_to_higher_type text not null check (lower_to_higher_type in ('PARENT', 'CHILD', 'SIBLING', 'SPOUSE', 'OTHER')),
    higher_to_lower_type text not null check (higher_to_lower_type in ('PARENT', 'CHILD', 'SIBLING', 'SPOUSE', 'OTHER')),
    created_at timestamptz not null default current_timestamp,
    created_by bigint references staff_accounts(id) on delete restrict,
    check (lower_member_id < higher_member_id),
    unique (lower_member_id, higher_member_id)
);

create index member_relationships_gym_idx on member_relationships (gym_id, id);
create index member_relationships_higher_member_idx on member_relationships (higher_member_id, lower_member_id);
create index member_relationships_created_by_idx on member_relationships (created_by);

create table member_consents (
    id bigint generated always as identity primary key,
    member_id bigint not null references members(id) on delete restrict,
    guardian_id bigint references guardians(id) on delete restrict,
    consent_type text not null
        check (consent_type in ('PRIVACY', 'MARKETING_SMS', 'KAKAO_NOTIFICATION', 'MINOR_GUARDIAN')),
    document_version text not null check (btrim(document_version) <> ''),
    agreed boolean not null,
    decided_at timestamptz not null,
    recorded_by bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp
);

create index member_consents_member_idx on member_consents (member_id, consent_type, decided_at desc, id desc);
create index member_consents_guardian_idx on member_consents (guardian_id);
create index member_consents_recorded_by_idx on member_consents (recorded_by);

create table member_notes (
    id bigint generated always as identity primary key,
    member_id bigint not null references members(id) on delete restrict,
    note_type text not null check (note_type in ('CONSULTATION', 'ADMIN')),
    content text not null check (btrim(content) <> ''),
    created_by bigint references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp
);

create index member_notes_member_idx on member_notes (member_id, created_at desc, id desc);
create index member_notes_created_by_idx on member_notes (created_by);
