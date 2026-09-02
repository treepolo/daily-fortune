-- Hardening before first deployment: serialize daily generation and make reroll retries idempotent
-- even when two requests race. Also expose an authenticated profile rollover RPC.

alter table public.personal_destiny_rerolls
    add constraint personal_parallel_source_range
    check (parallel_source_date between date '1900-01-01' and date '2100-12-31');

create or replace function public.commit_daily_zodiac_destinies(
    p_fortune_date date,
    p_engine_version text,
    p_ephemeris_version text,
    p_astronomy_snapshot jsonb,
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

    -- Two first requests after midnight may arrive together. Serialize only this date.
    perform pg_advisory_xact_lock(hashtext('daily-zodiac:' || p_fortune_date::text));

    select count(*) into existing_count
    from public.daily_zodiac_destinies
    where fortune_date = p_fortune_date;

    if existing_count = 12 then
        return query select * from public.daily_zodiac_destinies
        where fortune_date = p_fortune_date order by zodiac_sign;
        return;
    elsif existing_count <> 0 then
        raise exception 'Partial public destiny day exists for %', p_fortune_date;
    end if;

    insert into public.astronomy_snapshots (sky_date, ephemeris_version, snapshot)
    values (p_fortune_date, p_ephemeris_version, p_astronomy_snapshot)
    on conflict (sky_date, ephemeris_version) do nothing;

    insert into public.daily_zodiac_destinies (
        fortune_date, zodiac_sign, engine_version, ephemeris_version, astronomy_source_date,
        overall_grade, overall_score, domain_scores, domain_grades, explanations, astrology_factors
    )
    select
        p_fortune_date, row_data.zodiac_sign, p_engine_version, p_ephemeris_version, p_fortune_date,
        row_data.overall_grade, row_data.overall_score, row_data.domain_scores,
        row_data.domain_grades, row_data.explanations, row_data.astrology_factors
    from jsonb_to_recordset(p_rows) as row_data(
        zodiac_sign text,
        overall_grade text,
        overall_score double precision,
        domain_scores jsonb,
        domain_grades jsonb,
        explanations jsonb,
        astrology_factors jsonb
    );

    if (select count(*) from public.daily_zodiac_destinies where fortune_date = p_fortune_date) <> 12 then
        raise exception 'Public destiny batch did not produce 12 unique zodiac rows';
    end if;

    return query select * from public.daily_zodiac_destinies
    where fortune_date = p_fortune_date order by zodiac_sign;
end;
$$;

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
    p_source_date date := (p_payload->>'parallel_source_date')::date;
    p_ephemeris text := p_payload->>'ephemeris_version';
    current_count integer;
    current_zodiac text;
begin
    -- Fast idempotency path.
    select * into result_row from public.personal_destiny_rerolls
    where user_id = p_user_id and local_id = p_local_id;
    if found then return result_row; end if;

    if not exists (
        select 1 from public.daily_zodiac_destinies
        where fortune_date = p_date and zodiac_sign = p_zodiac
    ) then
        raise exception 'Public destiny must exist before private reroll';
    end if;

    if p_source_date < date '1900-01-01' or p_source_date > date '2100-12-31' then
        raise exception 'Parallel source date outside v1 range';
    end if;

    insert into public.astronomy_snapshots (sky_date, ephemeris_version, snapshot)
    values (p_source_date, p_ephemeris, p_payload->'astronomy_snapshot')
    on conflict (sky_date, ephemeris_version) do nothing;

    insert into public.daily_fortunes (user_id, fortune_date, zodiac_sign)
    values (p_user_id, p_date, p_zodiac)
    on conflict (user_id, fortune_date) do nothing;

    -- Serialize this user's rerolls for this day before assigning draw_index.
    select reroll_count, zodiac_sign into current_count, current_zodiac
    from public.daily_fortunes
    where user_id = p_user_id and fortune_date = p_date
    for update;

    if current_zodiac <> p_zodiac then
        raise exception 'Daily zodiac mismatch: existing %, requested %', current_zodiac, p_zodiac;
    end if;

    -- A duplicate local_id may have been committed while this request was waiting for the row lock.
    select * into result_row from public.personal_destiny_rerolls
    where user_id = p_user_id and local_id = p_local_id;
    if found then return result_row; end if;

    insert into public.personal_destiny_rerolls (
        user_id, local_id, fortune_date, zodiac_sign, draw_index, engine_version,
        ephemeris_version, parallel_source_date, original_sun_longitude,
        altered_sun_longitude, sun_longitude_difference, overall_grade, overall_score,
        domain_scores, domain_grades, explanations, astrology_factors
    ) values (
        p_user_id, p_local_id, p_date, p_zodiac, current_count + 1,
        p_payload->>'engine_version', p_ephemeris, p_source_date,
        (p_payload->>'original_sun_longitude')::double precision,
        (p_payload->>'altered_sun_longitude')::double precision,
        (p_payload->>'sun_longitude_difference')::double precision,
        p_payload->>'overall_grade', (p_payload->>'overall_score')::double precision,
        p_payload->'domain_scores', p_payload->'domain_grades',
        p_payload->'explanations', p_payload->'astrology_factors'
    ) returning * into result_row;

    update public.daily_fortunes
    set current_personal_reroll_id = result_row.id,
        reroll_count = result_row.draw_index,
        updated_at = now()
    where user_id = p_user_id and fortune_date = p_date;

    return result_row;
end;
$$;

-- Promote a pending zodiac when the next Taipei calendar day has arrived and return the profile.
create or replace function public.apply_due_zodiac()
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    result_row public.profiles;
    taipei_today date := (now() at time zone 'Asia/Taipei')::date;
begin
    update public.profiles
    set zodiac_sign = pending_zodiac_sign,
        pending_zodiac_sign = null,
        pending_zodiac_effective_date = null,
        updated_at = now()
    where id = auth.uid()
      and pending_zodiac_sign is not null
      and pending_zodiac_effective_date <= taipei_today;

    select * into result_row from public.profiles where id = auth.uid();
    return result_row;
end;
$$;

revoke all on function public.apply_due_zodiac() from public, anon;
grant execute on function public.apply_due_zodiac() to authenticated;
