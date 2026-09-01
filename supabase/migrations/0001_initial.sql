-- Daily Fortune initial cloud schema.
-- This migration is intentionally prepared before the Android client is linked to a real Supabase project.

create extension if not exists pgcrypto;

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.fortune_draws (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    local_id uuid not null,
    corpus_id text not null default 'guandi-100-v1',
    fortune_number smallint not null check (fortune_number between 1 and 100),
    normalized_grade text not null check (
        normalized_grade in ('DAI_JI', 'JI', 'XIAO_JI', 'PING', 'XIAO_XIONG', 'XIONG', 'DAI_XIONG')
    ),
    fortune_date date not null,
    draw_index integer not null check (draw_index >= 0),
    drawn_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    unique (user_id, local_id),
    unique (user_id, fortune_date, draw_index)
);

create table public.daily_fortunes (
    user_id uuid not null references auth.users(id) on delete cascade,
    fortune_date date not null,
    current_draw_id uuid not null references public.fortune_draws(id) on delete restrict,
    reroll_count integer not null default 0 check (reroll_count >= 0),
    updated_at timestamptz not null default now(),
    primary key (user_id, fortune_date)
);

create table public.fortune_bindings (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    draw_id uuid not null references public.fortune_draws(id) on delete cascade,
    message varchar(20),
    visibility text not null default 'private' check (visibility in ('private', 'public')),
    created_at timestamptz not null default now(),
    hidden_at timestamptz,
    unique (user_id, draw_id),
    check (message is null or char_length(message) <= 20)
);

create index fortune_draws_user_date_idx
    on public.fortune_draws (user_id, fortune_date, draw_index);
create index fortune_bindings_user_created_idx
    on public.fortune_bindings (user_id, created_at desc);

alter table public.profiles enable row level security;
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
