-- Daily Fortune initial cloud schema.
-- Prepared before linking the Android client to a real Supabase project.

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

-- Exactly one immutable public destiny per date and zodiac sign.
-- Each domain stores the fortune source number used to resolve its grade + prewritten explanation.
create table public.daily_zodiac_destinies (
    fortune_date date not null,
    zodiac_sign text not null check (zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    corpus_id text not null default 'guandi-100-v1',
    overall_fortune_number smallint not null check (overall_fortune_number between 1 and 100),
    wealth_fortune_number smallint not null check (wealth_fortune_number between 1 and 100),
    love_fortune_number smallint not null check (love_fortune_number between 1 and 100),
    work_study_fortune_number smallint not null check (work_study_fortune_number between 1 and 100),
    relationships_fortune_number smallint not null check (relationships_fortune_number between 1 and 100),
    health_fortune_number smallint not null check (health_fortune_number between 1 and 100),
    generated_at timestamptz not null default now(),
    primary key (fortune_date, zodiac_sign)
);

-- Personal rerolls only. The original daily public destiny is not duplicated here.
create table public.fortune_draws (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    local_id uuid not null,
    corpus_id text not null default 'guandi-100-v1',
    overall_fortune_number smallint not null check (overall_fortune_number between 1 and 100),
    wealth_fortune_number smallint not null check (wealth_fortune_number between 1 and 100),
    love_fortune_number smallint not null check (love_fortune_number between 1 and 100),
    work_study_fortune_number smallint not null check (work_study_fortune_number between 1 and 100),
    relationships_fortune_number smallint not null check (relationships_fortune_number between 1 and 100),
    health_fortune_number smallint not null check (health_fortune_number between 1 and 100),
    normalized_overall_grade text not null check (
        normalized_overall_grade in ('DAI_JI', 'JI', 'XIAO_JI', 'PING', 'XIAO_XIONG', 'XIONG', 'DAI_XIONG')
    ),
    fortune_date date not null,
    draw_index integer not null check (draw_index >= 1),
    drawn_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    unique (user_id, local_id),
    unique (user_id, fortune_date, draw_index)
);

create table public.daily_fortunes (
    user_id uuid not null references auth.users(id) on delete cascade,
    fortune_date date not null,
    zodiac_sign text not null check (zodiac_sign in (
        'ARIES', 'TAURUS', 'GEMINI', 'CANCER', 'LEO', 'VIRGO',
        'LIBRA', 'SCORPIO', 'SAGITTARIUS', 'CAPRICORN', 'AQUARIUS', 'PISCES'
    )),
    current_personal_draw_id uuid references public.fortune_draws(id) on delete restrict,
    reroll_count integer not null default 0 check (reroll_count >= 0),
    first_seen_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, fortune_date),
    foreign key (fortune_date, zodiac_sign)
        references public.daily_zodiac_destinies(fortune_date, zodiac_sign)
        on delete restrict
);

create table public.fortune_bindings (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    draw_id uuid references public.fortune_draws(id) on delete cascade,
    public_fortune_date date,
    public_zodiac_sign text,
    message varchar(20),
    visibility text not null default 'private' check (visibility in ('private', 'public')),
    created_at timestamptz not null default now(),
    hidden_at timestamptz,
    unique (user_id, draw_id),
    check (message is null or char_length(message) <= 20),
    check (
        (draw_id is not null and public_fortune_date is null and public_zodiac_sign is null)
        or
        (draw_id is null and public_fortune_date is not null and public_zodiac_sign is not null)
    ),
    foreign key (public_fortune_date, public_zodiac_sign)
        references public.daily_zodiac_destinies(fortune_date, zodiac_sign)
        on delete restrict
);

create index fortune_draws_user_date_idx
    on public.fortune_draws (user_id, fortune_date, draw_index);
create index fortune_bindings_user_created_idx
    on public.fortune_bindings (user_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.daily_zodiac_destinies enable row level security;
alter table public.fortune_draws enable row level security;
alter table public.daily_fortunes enable row level security;
alter table public.fortune_bindings enable row level security;

create policy "profiles_select_own"
    on public.profiles for select
    using (auth.uid() = id);
create policy "profiles_update_own"
    on public.profiles for update
    using (auth.uid() = id)
    with check (auth.uid() = id);

-- Public zodiac destinies are readable to authenticated users, including Supabase anonymous users.
-- No client write policy is created: generation/update belongs to a trusted server-side path.
create policy "daily_zodiac_destinies_select_authenticated"
    on public.daily_zodiac_destinies for select
    to authenticated
    using (true);

create policy "fortune_draws_select_own"
    on public.fortune_draws for select
    using (auth.uid() = user_id);
create policy "fortune_draws_insert_own"
    on public.fortune_draws for insert
    with check (auth.uid() = user_id);
create policy "fortune_draws_update_own"
    on public.fortune_draws for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
create policy "fortune_draws_delete_own"
    on public.fortune_draws for delete
    using (auth.uid() = user_id);

create policy "daily_fortunes_select_own"
    on public.daily_fortunes for select
    using (auth.uid() = user_id);
create policy "daily_fortunes_insert_own"
    on public.daily_fortunes for insert
    with check (auth.uid() = user_id);
create policy "daily_fortunes_update_own"
    on public.daily_fortunes for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
create policy "daily_fortunes_delete_own"
    on public.daily_fortunes for delete
    using (auth.uid() = user_id);

create policy "fortune_bindings_select_own"
    on public.fortune_bindings for select
    using (auth.uid() = user_id);
create policy "fortune_bindings_insert_own"
    on public.fortune_bindings for insert
    with check (auth.uid() = user_id);
create policy "fortune_bindings_update_own"
    on public.fortune_bindings for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
create policy "fortune_bindings_delete_own"
    on public.fortune_bindings for delete
    using (auth.uid() = user_id);

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
