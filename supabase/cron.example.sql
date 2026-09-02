-- Run this manually only after the Supabase project, Edge Function and Vault secrets exist.
-- Required Vault secret names:
--   project_url               = https://<PROJECT_REF>.supabase.co
--   publishable_key           = sb_publishable_...
--   daily_destiny_cron_secret = same value as Edge Function DAILY_DESTINY_CRON_SECRET
--
-- Asia/Taipei 00:00 = UTC 16:00 on the previous UTC calendar date.

select cron.schedule(
    'daily-fortune-taipei-midnight',
    '0 16 * * *',
    $$
    select net.http_post(
        url := (
            select decrypted_secret
            from vault.decrypted_secrets
            where name = 'project_url'
        ) || '/functions/v1/daily-destiny',
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'apikey', (
                select decrypted_secret
                from vault.decrypted_secrets
                where name = 'publishable_key'
            ),
            'x-cron-secret', (
                select decrypted_secret
                from vault.decrypted_secrets
                where name = 'daily_destiny_cron_secret'
            )
        ),
        body := '{"action":"ensure-public"}'::jsonb
    ) as request_id;
    $$
);

-- To remove the job later:
-- select cron.unschedule('daily-fortune-taipei-midnight');
