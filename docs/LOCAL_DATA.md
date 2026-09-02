# Local data architecture

The Android client now treats Room/SQLite as the durable local source of truth for fate data and history.

## Ownership

DataStore (`fortune_state`) keeps only small user settings. The existing `selected_zodiac` key is preserved across the Room rollout. Pre-Room daily fate, reroll counters, aggregate statistics, parallel-sky pointer, and retired ancient-fortune keys are cleared once because those aggregates cannot be losslessly converted into event history and are development-only data.

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

`DestinyAuthority` separates local persistence from the source of authoritative data.

Debug builds currently use `EmbeddedDevelopmentDestinyAuthority` so the existing APK remains testable before Supabase deployment. It calculates real astronomy locally and is explicitly marked `DEVELOPMENT_EMBEDDED` / `LOCAL_DEVELOPMENT` in Room.

Release builds do not use that source. Until Supabase is connected, `UnavailableDestinyAuthority` behaves as follows:

- cached central public destiny exists: load it from Room and continue offline;
- no cache: report unavailable;
- reroll without central service: report unavailable.

There is therefore no production seed or local-calculation fallback masquerading as a central public result.

## Multiple rerolls

Each reroll event stores both `beforeDestinyId` and `afterDestinyId`. The comparison shown after the second or later reroll is therefore:

`worldline immediately before the button press -> newly generated worldline`

It is not always compared with the original public destiny.

## Statistics

Statistics are derived from immutable Room events rather than mutable DataStore counters:

- one `PUBLIC_VIEW` sample ID per date;
- one `PERSONAL_REROLL` sample per reroll event;
- reroll totals and max-per-day from `local_reroll_events`;
- grade counts/rates from `local_fate_sample_events`.

This keeps discarded worldlines in history and makes future cloud reconciliation auditable.
