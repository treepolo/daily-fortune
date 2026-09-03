# Local data architecture

The Android client treats Room/SQLite as the durable local source of truth for fate data and history.

## Ownership

DataStore (`fortune_state`) keeps small user settings and retained pre-Room aggregate counters. The existing `selected_zodiac` key is preserved across the Room rollout. Legacy values are retained rather than deleted so an upgrade can still recover user-visible aggregate statistics if Room history is empty or temporarily unavailable.

Room database: `daily-fortune.db`.

Tables:

- `local_astronomy_samples`: one versioned 97-sample astronomical snapshot per source day.
- `local_destinies`: resolved public or personal astrology result, five domain scores/grades/explanations, engine/ephemeris versions and parallel-sky metadata.
- `local_astrology_factors`: complete factor audit and five-domain contributions for each destiny.
- `local_daily_fortunes`: effective zodiac, public destiny pointer, current private worldline and reroll count for a day.
- `local_reroll_events`: immutable local reroll history, including before/after destiny IDs and sync state.
- `local_fate_sample_events`: immutable samples used to derive statistics; replacing the current destiny never deletes history.
- `local_bindings`: local-first storage reserved for binding a fate result and its short note.

## Authority and offline rules

`DestinyAuthority` separates local persistence from the source of destiny data.

The currently distributed sideload APK, for both debug and release build types, uses `EmbeddedDevelopmentDestinyAuthority` until the Supabase client is actually wired into Android. It calculates the real astronomy locally with the same app astrology engine and stores provenance explicitly as `DEVELOPMENT_EMBEDDED` / `LOCAL_DEVELOPMENT`. These rows are never labeled as centrally synchronized results.

`UnavailableDestinyAuthority` remains reserved for a future central-only mode. It must not be selected merely because the APK is a release build; doing so would make an otherwise functional sideload release unable to generate the current day when no central cache exists.

If an authority request fails in the future, the UI first restores the locally persisted zodiac, statistics, cached public destiny, and current personal destiny where available, then surfaces the error. A transport failure must not make persisted data appear reset.

## Multiple rerolls

Each reroll event stores both `beforeDestinyId` and `afterDestinyId`. The comparison shown after the second or later reroll is therefore:

`worldline immediately before the button press -> newly generated worldline`

It is not always compared with the original public destiny.

## Statistics

Room statistics are derived from immutable events:

- one `PUBLIC_VIEW` sample ID per date;
- one `PERSONAL_REROLL` sample per reroll event;
- reroll totals and max-per-day from `local_reroll_events`;
- grade counts/rates from `local_fate_sample_events`.

For upgrade compatibility, any retained pre-Room aggregate statistic is compared with the Room-derived value and the larger value is used for display. This prevents a storage migration from making an existing user's counters go backwards while Room remains the source of detailed event history.

This keeps discarded worldlines in history and makes future cloud reconciliation auditable.
