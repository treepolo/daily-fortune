# Supabase Research Backend Deployment

This backend is optional for core fortune drawing. With no endpoints configured, the Android app remains fully functional using the embedded default experiment config and keeps analytics events locally queued.

## 1. Apply schema

```bash
supabase db push
```

The schema contains only:

- `app_config`
- `experiments`
- `experiment_variants`
- `analytics_events`

The tables have RLS enabled with no anonymous table policies. Android clients access them only through Edge Functions.

## 2. Deploy Edge Functions

```bash
supabase functions deploy experiment-config --no-verify-jwt
supabase functions deploy analytics-events --no-verify-jwt
```

Both functions use the server-provided Supabase service-role environment internally. The service-role secret must never be placed in the Android app.

## 3. Android endpoint configuration

Release builds read two environment variables:

```text
DAILY_FORTUNE_CONFIG_URL=https://<project>.supabase.co/functions/v1/experiment-config
DAILY_FORTUNE_ANALYTICS_URL=https://<project>.supabase.co/functions/v1/analytics-events
```

For GitHub Actions, create repository secrets with the same names. The workflow passes them only into the Android build step.

After one distributed APK contains these endpoint URLs, changing experiment variants, traffic rollout, probability distributions, correlation matrices, overall rounding rules, or other already-supported parameters does not require another APK update.

## 4. Base configuration

Migration seed data installs the current product default:

- five independent domains
- uniform 1..7 probability (1/7 each)
- identical initial/reroll distribution
- overall `FLOOR`
- baseline visual IDs

Update `app_config.config` to change the global base configuration. Use experiments when measuring alternatives.

## 5. Creating an A/B/n experiment

Create one `experiments` row and any number of `experiment_variants` rows.

Example structure:

```sql
insert into experiments (id, status, rollout, salt, priority)
values ('fortune_probability_v1', 'ACTIVE', 1.0, 'replace-with-stable-random-salt', 10);

insert into experiment_variants (experiment_id, variant_id, weight, treatment)
values
  ('fortune_probability_v1', 'A', 1, '{"fortune":{"initial_distribution":{"id":"uniform-v1","probabilities":[0.1428571429,0.1428571429,0.1428571429,0.1428571429,0.1428571429,0.1428571429,0.1428571429]}}}'),
  ('fortune_probability_v1', 'B', 1, '{"fortune":{"initial_distribution":{"id":"center-heavy-v1","probabilities":[0.05,0.10,0.15,0.40,0.15,0.10,0.05]}}}'),
  ('fortune_probability_v1', 'C', 1, '{"fortune":{"initial_distribution":{"id":"extreme-heavy-v1","probabilities":[0.25,0.10,0.05,0.20,0.05,0.10,0.25]}}}');
```

Variant weights are relative; they do not need to sum to 1. `1,2,1` means 25%, 50%, 25% among installations admitted by the experiment rollout.

Enrollment and arm selection use separate deterministic hashes. This means `rollout` can be ramped upward during one experiment without moving already-enrolled installations between A/B/C/... arms.

Within one experiment version, keep `id`, `salt`, variant identities, and variant weights fixed. If arm weights, arm definitions, or assignment semantics need to change, create a new experiment ID/version (and normally a new salt). This prevents treatment crossover and keeps analysis interpretable.

## 6. Analytics

The Android app posts batches of up to 50 queued events to `analytics-events`. `event_id` is the idempotency key, so retrying a batch does not duplicate canonical events.

Analysis is intentionally outside the Android application. Query `analytics_events` from SQL, Python, notebooks, or a private dashboard.
