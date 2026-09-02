-- Daily Fortune initial cloud schema.
-- This migration is intentionally written before the first real Supabase deployment.
-- Both public fate and private rerolls are astrology-based; the retired ancient-fortune model is gone.

create extension if not exists pgcrypto;

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text,
    zodiac_sign text check (zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    pending_zodiac_sign text check (pending_zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    pending_zodiac_effective_date date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (
        (pending_zodiac_sign is null and pending_zodiac_effective_date is null)
        or
        (pending_zodiac_sign is not null and pending_zodiac_effective_date is not null)
    )
);

-- One immutable, server-authoritative public astrology destiny per date + zodiac.
create table public.daily_zodiac_destinies (
    fortune_date date not null,
    zodiac_sign text not null check (zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    engine_version text not null,
    overall_grade text not null check (
        overall_grade in ('DAI_JI', 'JI', 'XIAO_JI', 'PING', 'XIAO_XIONG', 'XIONG', 'DAI_XIONG')
    ),
    overall_score double precision not null,
    domain_scores jsonb not null check (jsonb_typeof(domain_scores) = 'object'),
    domain_grades jsonb not null check (jsonb_typeof(domain_grades) = 'object'),
    explanations jsonb not null check (jsonb_typeof(explanations) = 'object'),
    astronomy_snapshot jsonb not null check (jsonb_typeof(astronomy_snapshot) = 'object'),
    astrology_factors jsonb not null check (jsonb_typeof(astrology_factors) = 'array'),
    generated_at timestamptz not null default now(),
    primary key (fortune_date, zodiac_sign)
);

-- Every private "逆天改命" result is another real 24-hour sky, selected by secure server randomness,
-- then evaluated by the same versioned Astrology Engine.
create table public.personal_destiny_rerolls (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    local_id uuid not null,
    fortune_date date not null,
    zodiac_sign text not null check (zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    draw_index integer not null check (draw_index >= 1),
    engine_version text not null,
    parallel_source_date date not null,
    original_sun_longitude double precision not null check (original_sun_longitude >= 0 and original_sun_longitude < 360),
    altered_sun_longitude double precision not null check (altered_sun_longitude >= 0 and altered_sun_longitude < 360),
    sun_longitude_difference double precision not null check (sun_longitude_difference >= 0 and sun_longitude_difference <= 2),
    overall_grade text not null check (
        overall_grade in ('DAI_JI', 'JI', 'XIAO_JI', 'PING', 'XIAO_XIONG', 'XIONG', 'DAI_XIONG')
    ),
    overall_score double precision not null,
    domain_scores jsonb not null check (jsonb_typeof(domain_scores) = 'object'),
    domain_grades jsonb not null check (jsonb_typeof(domain_grades) = 'object'),
    explanations jsonb not null check (jsonb_typeof(explanations) = 'object'),
    astronomy_snapshot jsonb not null check (jsonb_typeof(astronomy_snapshot) = 'object'),
    astrology_factors jsonb not null check (jsonb_typeof(astrology_factors) = 'array'),
    created_at timestamptz not null default now(),
    unique (user_id, local_id),
    unique (user_id, fortune_date, draw_index),
    foreign key (fortune_date, zodiac_sign)
        references public.daily_zodiac_destinies(fortune_date, zodiac_sign)
        on delete restrict
);

create table public.daily_fortunes (
    user_id uuid not null references auth.users(id) on delete cascade,
    fortune_date date not null,
    zodiac_sign text not null check (zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    current_personal_reroll_id uuid references public.personal_destiny_rerolls(id) on delete restrict,
    reroll_count integer not null default 0 check (reroll_count >= 0),
    first_seen_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, fortune_date),
    foreign key (fortune_date, zodiac_sign)
        references public.daily_zodiac_destinies(fortune_date, zodiac_sign)
        on delete restrict
);

-- Future "bind this bad fate" feature. Text remains private until public UGC moderation exists.
create table public.destiny_bindings (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    personal_reroll_id uuid references public.personal_destiny_rerolls(id) on delete cascade,
    public_fortune_date date,
    public_zodiac_sign text,
    message varchar(20),
    visibility text not null default 'private' check (visibility in ('private', 'public')),
    created_at timestamptz not null default now(),
    hidden_at timestamptz,
    check (message is null or char_length(message) <= 20),
    check (
        (personal_reroll_id is not null and public_fortune_date is null and public_zodiac_sign is null)
        or
        (personal_reroll_id is null and public_fortune_date is not null and public_zodiac_sign is not null)
    ),
    foreign key (public_fortune_date, public_zodiac_sign)
        references public.daily_zodiac_destinies(fortune_date, zodiac_sign)
        on delete restrict
);

create unique index destiny_bindings_personal_unique
    on public.destiny_bindings (user_id, personal_reroll_id)
    where personal_reroll_id is not null;
create unique index destiny_bindings_public_unique
    on public.destiny_bindings (user_id, public_fortune_date, public_zodiac_sign)
    where public_fortune_date is not null;
create index personal_destiny_rerolls_user_date_idx
    on public.personal_destiny_rerolls (user_id, fortune_date, draw_index);
create index destiny_bindings_user_created_idx
    on public.destiny_bindings (user_id, created_at desc);
create index daily_zodiac_destinies_engine_idx
    on public.daily_zodiac_destinies (engine_version, fortune_date desc);

alter table public.profiles enable row level security;
alter table public.daily_zodiac_destinies enable row level security;
alter table public.personal_destiny_rerolls enable row level security;
alter table public.daily_fortunes enable row level security;
alter table public.destiny_bindings enable row level security;

create policy "profiles_select_own"
    on public.profiles for select
    using (auth.uid() = id);

-- Public destinies are readable but never client-writable.
create policy "daily_zodiac_destinies_select_authenticated"
    on public.daily_zodiac_destinies for select
    to authenticated
    using (true);

create policy "personal_destiny_rerolls_select_own"
    on public.personal_destiny_rerolls for select
    using (auth.uid() = user_id);

create policy "daily_fortunes_select_own"
    on public.daily_fortunes for select
    using (auth.uid() = user_id);

create policy "destiny_bindings_select_own"
    on public.destiny_bindings for select
    using (auth.uid() = user_id);
create policy "destiny_bindings_insert_own"
    on public.destiny_bindings for insert
    with check (auth.uid() = user_id and visibility = 'private');
create policy "destiny_bindings_update_own"
    on public.destiny_bindings for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id and visibility = 'private');
create policy "destiny_bindings_delete_own"
    on public.destiny_bindings for delete
    using (auth.uid() = user_id);

-- Public rows are immutable after insertion, including for accidental service-role updates.
create or replace function public.reject_public_destiny_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'daily_zodiac_destinies rows are immutable';
end;
$$;

create trigger daily_zodiac_destinies_immutable
before update or delete on public.daily_zodiac_destinies
for each row execute function public.reject_public_destiny_mutation();

-- Trusted Edge Function commits all 12 zodiac rows as one transaction. Existing complete days are
-- returned unchanged. A partial pre-existing day is treated as corruption instead of silently patched.
create or replace function public.commit_daily_zodiac_destinies(
    p_fortune_date date,
    p_engine_version text,
    p_rows jsonb
)
returns setof public.daily_zodiac_destinies
language plpgsql
security definer
set search_path = public
as $$
declare
    existing_count integer;
begin
    if jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) <> 12 then
        raise exception 'Expected exactly 12 zodiac destiny rows';
    end if;

    select count(*) into existing_count
    from public.daily_zodiac_destinies
    where fortune_date = p_fortune_date;

    if existing_count = 12 then
        return query
        select * from public.daily_zodiac_destinies
        where fortune_date = p_fortune_date
        order by zodiac_sign;
        return;
    elsif existing_count <> 0 then
        raise exception 'Partial public destiny day exists for %', p_fortune_date;
    end if;

    insert into public.daily_zodiac_destinies (
        fortune_date, zodiac_sign, engine_version, overall_grade, overall_score,
        domain_scores, domain_grades, explanations, astronomy_snapshot, astrology_factors
    )
    select
        p_fortune_date,
        row_data.zodiac_sign,
        p_engine_version,
        row_data.overall_grade,
        row_data.overall_score,
        row_data.domain_scores,
        row_data.domain_grades,
        row_data.explanations,
        row_data.astronomy_snapshot,
        row_data.astrology_factors
    from jsonb_to_recordset(p_rows) as row_data(
        zodiac_sign text,
        overall_grade text,
        overall_score double precision,
        domain_scores jsonb,
        domain_grades jsonb,
        explanations jsonb,
        astronomy_snapshot jsonb,
        astrology_factors jsonb
    );

    if (select count(*) from public.daily_zodiac_destinies where fortune_date = p_fortune_date) <> 12 then
        raise exception 'Public destiny batch did not produce 12 unique zodiac rows';
    end if;

    return query
    select * from public.daily_zodiac_destinies
    where fortune_date = p_fortune_date
    order by zodiac_sign;
end;
$$;

-- Trusted Edge Function atomically appends one private reroll and moves the user's current worldline.
create or replace function public.commit_personal_reroll(
    p_user_id uuid,
    p_local_id uuid,
    p_payload jsonb
)
returns public.personal_destiny_rerolls
language plpgsql
security definer
set search_path = public
as $$
declare
    result_row public.personal_destiny_rerolls;
    p_date date := (p_payload->>'fortune_date')::date;
    p_zodiac text := p_payload->>'zodiac_sign';
    current_count integer;
begin
    select * into result_row
    from public.personal_destiny_rerolls
    where user_id = p_user_id and local_id = p_local_id;
    if found then
        return result_row;
    end if;

    if not exists (
        select 1 from public.daily_zodiac_destinies
        where fortune_date = p_date and zodiac_sign = p_zodiac
    ) then
        raise exception 'Public destiny must exist before private reroll';
    end if;

    insert into public.daily_fortunes (user_id, fortune_date, zodiac_sign)
    values (p_user_id, p_date, p_zodiac)
    on conflict (user_id, fortune_date) do nothing;

    select reroll_count into current_count
    from public.daily_fortunes
    where user_id = p_user_id and fortune_date = p_date
    for update;

    insert into public.personal_destiny_rerolls (
        user_id, local_id, fortune_date, zodiac_sign, draw_index, engine_version,
        parallel_source_date, original_sun_longitude, altered_sun_longitude,
        sun_longitude_difference, overall_grade, overall_score, domain_scores,
        domain_grades, explanations, astronomy_snapshot, astrology_factors
    ) values (
        p_user_id,
        p_local_id,
        p_date,
        p_zodiac,
        current_count + 1,
        p_payload->>'engine_version',
        (p_payload->>'parallel_source_date')::date,
        (p_payload->>'original_sun_longitude')::double precision,
        (p_payload->>'altered_sun_longitude')::double precision,
        (p_payload->>'sun_longitude_difference')::double precision,
        p_payload->>'overall_grade',
        (p_payload->>'overall_score')::double precision,
        p_payload->'domain_scores',
        p_payload->'domain_grades',
        p_payload->'explanations',
        p_payload->'astronomy_snapshot',
        p_payload->'astrology_factors'
    ) returning * into result_row;

    update public.daily_fortunes
    set current_personal_reroll_id = result_row.id,
        reroll_count = result_row.draw_index,
        updated_at = now()
    where user_id = p_user_id and fortune_date = p_date;

    return result_row;
end;
$$;

-- First zodiac choice is immediate. Later choices become effective next Asia/Taipei calendar day.
create or replace function public.set_or_request_zodiac(p_zodiac text)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    result_row public.profiles;
    tomorrow date := ((now() at time zone 'Asia/Taipei')::date + 1);
begin
    if p_zodiac not in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    ) then
        raise exception 'Invalid zodiac';
    end if;

    update public.profiles
    set zodiac_sign = case when zodiac_sign is null then p_zodiac else zodiac_sign end,
        pending_zodiac_sign = case when zodiac_sign is null then null else p_zodiac end,
        pending_zodiac_effective_date = case when zodiac_sign is null then null else tomorrow end,
        updated_at = now()
    where id = auth.uid()
    returning * into result_row;

    return result_row;
end;
$$;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = ''
as $$
begin
    insert into public.profiles (id) values (new.id)
    on conflict (id) do nothing;
    return new;
end;
$$;

create trigger on_auth_user_created
after insert on auth.users
for each row execute procedure public.handle_new_user();

revoke all on function public.commit_daily_zodiac_destinies(date, text, jsonb) from public, anon, authenticated;
revoke all on function public.commit_personal_reroll(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function public.commit_daily_zodiac_destinies(date, text, jsonb) to service_role;
grant execute on function public.commit_personal_reroll(uuid, uuid, jsonb) to service_role;
grant execute on function public.set_or_request_zodiac(text) to authenticated;
