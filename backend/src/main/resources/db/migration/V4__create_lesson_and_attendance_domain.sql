create table lesson_templates (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    name text not null check (btrim(name) <> ''),
    lesson_type text not null check (lesson_type in ('GENERAL', 'PT')),
    instructor_account_id bigint not null references staff_accounts(id) on delete restrict,
    start_time time not null,
    end_time time not null,
    active_from date not null,
    active_until date,
    display_color text not null check (display_color ~ '^#[0-9A-Fa-f]{6}$'),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default current_timestamp,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint not null references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    check (start_time < end_time),
    check (active_until is null or active_from <= active_until)
);

create index lesson_templates_gym_status_idx on lesson_templates (gym_id, status, active_from, id);
create index lesson_templates_instructor_idx on lesson_templates (instructor_account_id, status, id);
create index lesson_templates_created_by_idx on lesson_templates (created_by);
create index lesson_templates_updated_by_idx on lesson_templates (updated_by);

create table lesson_template_days (
    lesson_template_id bigint not null references lesson_templates(id) on delete restrict,
    day_of_week smallint not null check (day_of_week between 1 and 7),
    primary key (lesson_template_id, day_of_week)
);

create table lesson_target_groups (
    lesson_template_id bigint not null references lesson_templates(id) on delete restrict,
    member_group_id bigint not null references member_groups(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    primary key (lesson_template_id, member_group_id)
);

create index lesson_target_groups_group_idx on lesson_target_groups (member_group_id, lesson_template_id);
create index lesson_target_groups_created_by_idx on lesson_target_groups (created_by);

create table lesson_pt_assignments (
    id bigint generated always as identity primary key,
    lesson_template_id bigint not null references lesson_templates(id) on delete restrict,
    member_id bigint not null references members(id) on delete restrict,
    membership_id bigint not null references memberships(id) on delete restrict,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    assigned_at timestamptz not null default current_timestamp,
    ended_at timestamptz,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    check ((status = 'ACTIVE' and ended_at is null)
        or (status = 'INACTIVE' and ended_at is not null and ended_at >= assigned_at))
);

create unique index lesson_pt_assignments_one_active_uidx on lesson_pt_assignments (lesson_template_id)
    where status = 'ACTIVE';
create index lesson_pt_assignments_template_idx on lesson_pt_assignments (lesson_template_id, id);
create index lesson_pt_assignments_member_idx on lesson_pt_assignments (member_id, status, id);
create index lesson_pt_assignments_membership_idx on lesson_pt_assignments (membership_id, status, id);
create index lesson_pt_assignments_created_by_idx on lesson_pt_assignments (created_by);

create table lesson_sessions (
    id bigint generated always as identity primary key,
    lesson_template_id bigint not null references lesson_templates(id) on delete restrict,
    gym_id bigint not null references gyms(id) on delete restrict,
    session_date date not null,
    starts_at time not null,
    ends_at time not null,
    status text not null default 'SCHEDULED' check (status in ('SCHEDULED', 'COMPLETED', 'CANCELLED')),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    updated_by bigint references staff_accounts(id) on delete restrict,
    version bigint not null default 0,
    unique (lesson_template_id, session_date, starts_at),
    check (starts_at < ends_at)
);

create index lesson_sessions_gym_calendar_idx on lesson_sessions (gym_id, session_date, starts_at, id);
create index lesson_sessions_updated_by_idx on lesson_sessions (updated_by);

create table attendances (
    id bigint generated always as identity primary key,
    gym_id bigint not null references gyms(id) on delete restrict,
    member_id bigint not null references members(id) on delete restrict,
    lesson_session_id bigint references lesson_sessions(id) on delete restrict,
    membership_id bigint references memberships(id) on delete restrict,
    count_debit_entry_id bigint unique references membership_count_entries(id) on delete restrict,
    attendance_type text not null check (attendance_type in ('FREE', 'GENERAL_CLASS', 'PT_CLASS')),
    business_date date not null,
    original_check_in_at timestamptz not null,
    original_check_out_at timestamptz,
    current_check_in_at timestamptz not null,
    current_check_out_at timestamptz,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'CANCELLED')),
    counts_for_daily_total boolean not null default true,
    created_by bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    check (original_check_out_at is null or original_check_in_at <= original_check_out_at),
    check (current_check_out_at is null or current_check_in_at <= current_check_out_at),
    check ((attendance_type = 'FREE' and lesson_session_id is null)
        or (attendance_type in ('GENERAL_CLASS', 'PT_CLASS') and lesson_session_id is not null)),
    check ((attendance_type = 'PT_CLASS' and membership_id is not null)
        or attendance_type <> 'PT_CLASS'),
    check (attendance_type = 'PT_CLASS' or count_debit_entry_id is null),
    check (status = 'ACTIVE' or not counts_for_daily_total)
);

create index attendances_member_history_idx on attendances (member_id, business_date desc, id desc);
create index attendances_gym_history_idx on attendances (gym_id, business_date desc, id desc);
create index attendances_lesson_session_idx on attendances (lesson_session_id, status, id)
    where lesson_session_id is not null;
create index attendances_membership_idx on attendances (membership_id, business_date desc, id desc)
    where membership_id is not null;
create index attendances_created_by_idx on attendances (created_by, business_date desc, id desc);
create unique index attendances_daily_total_uidx on attendances (gym_id, member_id, business_date)
    where counts_for_daily_total and status = 'ACTIVE';
create unique index attendances_pt_session_uidx on attendances (member_id, lesson_session_id)
    where status = 'ACTIVE' and attendance_type = 'PT_CLASS';

create table attendance_adjustments (
    id bigint generated always as identity primary key,
    attendance_id bigint not null references attendances(id) on delete restrict,
    adjustment_type text not null check (adjustment_type in ('CANCEL', 'TIME_CORRECTION')),
    before_check_in_at timestamptz not null,
    after_check_in_at timestamptz,
    before_check_out_at timestamptz,
    after_check_out_at timestamptz,
    reason text not null check (btrim(reason) <> ''),
    actor_account_id bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    check ((adjustment_type = 'CANCEL' and after_check_in_at is null and after_check_out_at is null)
        or (adjustment_type = 'TIME_CORRECTION' and after_check_in_at is not null)),
    check (before_check_out_at is null or before_check_in_at <= before_check_out_at),
    check (after_check_out_at is null or (after_check_in_at is not null and after_check_in_at <= after_check_out_at))
);

create index attendance_adjustments_attendance_idx on attendance_adjustments (attendance_id, created_at desc, id desc);
create unique index attendance_adjustments_one_cancel_uidx on attendance_adjustments (attendance_id)
    where adjustment_type = 'CANCEL';
create index attendance_adjustments_actor_idx on attendance_adjustments (actor_account_id);

create table attendance_exceptions (
    id bigint generated always as identity primary key,
    attendance_id bigint not null references attendances(id) on delete restrict,
    membership_id bigint not null references memberships(id) on delete restrict,
    restriction_type text not null check (restriction_type in ('OVERDUE_PAYMENT', 'BEFORE_FULL_PAYMENT')),
    outstanding_amount_won bigint not null check (outstanding_amount_won >= 0),
    reason text not null check (btrim(reason) <> ''),
    approved_by bigint not null references staff_accounts(id) on delete restrict,
    created_at timestamptz not null default current_timestamp,
    unique (attendance_id, restriction_type)
);

create index attendance_exceptions_membership_idx on attendance_exceptions (membership_id, created_at desc, id desc);
create index attendance_exceptions_approved_by_idx on attendance_exceptions (approved_by);
