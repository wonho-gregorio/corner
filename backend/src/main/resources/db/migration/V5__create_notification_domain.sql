create table notification_senders (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    provider text not null check (provider in ('SOLAPI')),
    provider_sender_id text not null check (btrim(provider_sender_id) <> ''),
    phone text not null check (btrim(phone) <> ''),
    normalized_phone text not null check (normalized_phone ~ '^[0-9]+$'),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default current_timestamp,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint not null references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    unique (gym_id, provider, provider_sender_id),
    unique (gym_id, normalized_phone)
);

create index notification_senders_created_by_idx on notification_senders (created_by);
create index notification_senders_updated_by_idx on notification_senders (updated_by);

create table notification_test_recipients (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    name text not null check (btrim(name) <> ''),
    phone text not null check (btrim(phone) <> ''),
    normalized_phone text not null check (normalized_phone ~ '^[0-9]+$'),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default current_timestamp,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint not null references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    unique (gym_id, normalized_phone)
);

create index notification_test_recipients_created_by_idx on notification_test_recipients (created_by);
create index notification_test_recipients_updated_by_idx on notification_test_recipients (updated_by);

create table notification_setting_versions (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    version_no integer not null check (version_no > 0),
    enabled boolean not null default false,
    send_time time not null,
    title_template text not null check (btrim(title_template) <> ''),
    body_template text not null check (btrim(body_template) <> ''),
    sender_id bigint references notification_senders(id) on delete restrict,
    active_from timestamptz not null default current_timestamp,
    retired_at timestamptz,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    unique (gym_id, version_no),
    check (not enabled or sender_id is not null),
    check (retired_at is null or retired_at >= active_from)
);

create unique index notification_setting_versions_current_uidx on notification_setting_versions (gym_id)
    where retired_at is null;
create index notification_setting_versions_sender_idx on notification_setting_versions (sender_id)
    where sender_id is not null;
create index notification_setting_versions_created_by_idx on notification_setting_versions (created_by);

create table notification_jobs (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    member_id bigint references members(id) on delete restrict,
    membership_id bigint references memberships(id) on delete restrict,
    test_recipient_id bigint references notification_test_recipients(id) on delete restrict,
    setting_version_id bigint not null references notification_setting_versions(id) on delete restrict,
    job_type text not null check (job_type in ('REREGISTRATION', 'TEST')),
    reregistration_date date,
    recipient_name text not null check (btrim(recipient_name) <> ''),
    recipient_phone_masked text not null check (btrim(recipient_phone_masked) <> ''),
    recipient_phone_ciphertext text not null check (btrim(recipient_phone_ciphertext) <> ''),
    recipient_phone_hash text not null check (btrim(recipient_phone_hash) <> ''),
    title_snapshot text not null check (btrim(title_snapshot) <> ''),
    body_snapshot text not null check (btrim(body_snapshot) <> ''),
    message_type text not null check (message_type in ('SMS', 'LMS')),
    status text not null default 'PENDING'
        check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')),
    scheduled_at timestamptz not null,
    next_attempt_at timestamptz,
    attempt_count integer not null default 0 check (attempt_count >= 0),
    claimed_at timestamptz,
    claimed_by text,
    sent_at timestamptz,
    failure_code text,
    cancelled_at timestamptz,
    cancellation_reason text,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint notification_jobs_target_ck check (
        (job_type = 'REREGISTRATION'
            and member_id is not null
            and membership_id is not null
            and test_recipient_id is null
            and reregistration_date is not null)
        or
        (job_type = 'TEST'
            and member_id is null
            and membership_id is null
            and test_recipient_id is not null
            and reregistration_date is null)
    ),
    check ((status = 'PENDING' and next_attempt_at is not null and claimed_at is null and sent_at is null)
        or (status = 'PROCESSING' and next_attempt_at is null and claimed_at is not null and sent_at is null)
        or (status = 'SENT' and next_attempt_at is null and sent_at is not null)
        or (status = 'FAILED' and sent_at is null)
        or (status = 'CANCELLED' and next_attempt_at is null and sent_at is null and cancelled_at is not null)),
    check ((status = 'PROCESSING' and claimed_by is not null and btrim(claimed_by) <> '')
        or status <> 'PROCESSING'),
    check (failure_code is null or btrim(failure_code) <> ''),
    check (cancellation_reason is null or btrim(cancellation_reason) <> ''),
    check ((status = 'CANCELLED' and cancellation_reason is not null)
        or (status <> 'CANCELLED' and cancelled_at is null and cancellation_reason is null)),
    check (sent_at is null or sent_at >= scheduled_at),
    check (cancelled_at is null or cancelled_at >= created_at)
);

create unique index notification_jobs_reregistration_uidx
    on notification_jobs (gym_id, member_id, reregistration_date, job_type)
    where job_type = 'REREGISTRATION';
create index notification_jobs_claim_idx on notification_jobs (status, next_attempt_at, id)
    where status in ('PENDING', 'FAILED') and next_attempt_at is not null;
create index notification_jobs_gym_history_idx on notification_jobs (gym_id, scheduled_at desc, id desc);
create index notification_jobs_phone_search_idx
    on notification_jobs (gym_id, recipient_phone_hash, scheduled_at desc, id desc);
create index notification_jobs_member_idx on notification_jobs (member_id, scheduled_at desc, id desc)
    where member_id is not null;
create index notification_jobs_membership_idx on notification_jobs (membership_id, id)
    where membership_id is not null;
create index notification_jobs_test_recipient_idx on notification_jobs (test_recipient_id, id)
    where test_recipient_id is not null;
create index notification_jobs_setting_version_idx on notification_jobs (setting_version_id, id);

create table notification_attempts (
    id bigint generated always as identity primary key,
    job_id bigint not null references notification_jobs(id) on delete restrict,
    attempt_no integer not null check (attempt_no > 0),
    requested_at timestamptz not null,
    completed_at timestamptz,
    provider_message_id text,
    result text check (result is null or result in ('SUCCESS', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE')),
    failure_code text,
    provider_response jsonb,
    created_at timestamptz not null default current_timestamp,
    unique (job_id, attempt_no),
    check (completed_at is null or completed_at >= requested_at),
    check (provider_message_id is null or btrim(provider_message_id) <> ''),
    check (failure_code is null or btrim(failure_code) <> ''),
    check ((completed_at is null and result is null and failure_code is null)
        or (completed_at is not null and result = 'SUCCESS' and failure_code is null)
        or (completed_at is not null and result in ('TEMPORARY_FAILURE', 'PERMANENT_FAILURE') and failure_code is not null))
);
