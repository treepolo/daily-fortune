-- Daily Fortune v2: remote experimentation and anonymous analytics.
-- The previous astrology schema was never deployed and is retired from the product.

create table if not exists public.app_config (
  id text primary key,
  config jsonb not null,
  updated_at timestamptz not null default now()
);

create table if not exists public.experiments (
  id text primary key,
  status text not null default 'DRAFT' check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'ENDED')),
  rollout double precision not null default 1.0 check (rollout >= 0.0 and rollout <= 1.0),
  salt text not null,
  priority integer not null default 0,
  starts_at timestamptz,
  ends_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.experiment_variants (
  experiment_id text not null references public.experiments(id) on delete cascade,
  variant_id text not null,
  weight double precision not null check (weight > 0.0),
  treatment jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (experiment_id, variant_id)
);

create table if not exists public.analytics_events (
  event_id uuid primary key,
  installation_id uuid not null,
  session_id text not null,
  event_name text not null,
  event_epoch_millis bigint not null,
  event_at timestamptz not null,
  local_datetime text not null,
  timezone_id text not null,
  app_version text not null,
  config_id text not null,
  assignments jsonb not null default '[]'::jsonb,
  payload jsonb not null default '{}'::jsonb,
  received_at timestamptz not null default now()
);

create index if not exists analytics_events_installation_time_idx
  on public.analytics_events (installation_id, event_at);
create index if not exists analytics_events_name_time_idx
  on public.analytics_events (event_name, event_at);
create index if not exists analytics_events_config_time_idx
  on public.analytics_events (config_id, event_at);
create index if not exists experiments_status_priority_idx
  on public.experiments (status, priority);

alter table public.app_config enable row level security;
alter table public.experiments enable row level security;
alter table public.experiment_variants enable row level security;
alter table public.analytics_events enable row level security;

-- No client-facing table policies are created. Edge Functions use the service role;
-- the Android app talks only to those controlled endpoints.

insert into public.app_config (id, config)
values (
  'default',
  jsonb_build_object(
    'fortune', jsonb_build_object(
      'initial_distribution', jsonb_build_object(
        'id', 'uniform-v1',
        'probabilities', jsonb_build_array(1.0/7,1.0/7,1.0/7,1.0/7,1.0/7,1.0/7,1.0/7)
      ),
      'reroll_distribution', jsonb_build_object(
        'id', 'uniform-v1',
        'probabilities', jsonb_build_array(1.0/7,1.0/7,1.0/7,1.0/7,1.0/7,1.0/7,1.0/7)
      ),
      'sampling', jsonb_build_object(
        'mode', 'INDEPENDENT',
        'profile_id', 'independent-v1'
      ),
      'overall_rule', jsonb_build_object(
        'id', 'floor-v1',
        'type', 'FLOOR'
      )
    ),
    'visual', jsonb_build_object(
      'static_variant_id', 'baseline',
      'reveal_variant_id', 'none'
    )
  )
)
on conflict (id) do update
set config = excluded.config,
    updated_at = now();
