import {
  ASTRO_BODIES,
  DOMAINS,
  ZODIACS,
  calculateDay,
  type AstroBody,
  type FortuneDomain,
  type ZodiacSign,
} from "../supabase/functions/_shared/astrology_v1.ts";

const shard = Number(Deno.args[0] ?? "0");
const shardCount = Number(Deno.args[1] ?? "1");
if (!Number.isInteger(shard) || !Number.isInteger(shardCount) || shard < 0 || shard >= shardCount) {
  throw new Error("usage: astrology_weight_features_v3.ts <shard> <shardCount>");
}

const ASPECT_NAMES = ["CONJUNCTION", "SEXTILE", "SQUARE", "TRINE", "OPPOSITION"] as const;
const BODY_COLUMNS = ASTRO_BODIES.length * DOMAINS.length;
const HOUSE_COLUMNS = 12 * DOMAINS.length;
const ASPECT_COLUMNS = ASPECT_NAMES.length * DOMAINS.length;
const COLUMNS = BODY_COLUMNS + HOUSE_COLUMNS + ASPECT_COLUMNS;

const selectedYears = Array.from({ length: 2100 - 1900 + 1 }, (_, i) => 1900 + i)
  .filter((year) => (year - 1900) % shardCount === shard);
const expectedRows = selectedYears.reduce((sum, year) => sum + daysInYear(year) * ZODIACS.length, 0);
const values = new Float32Array(expectedRows * COLUMNS);
let cursor = 0;
let rows = 0;

for (const year of selectedYears) {
  let date = `${year}-01-01`;
  const end = `${year}-12-31`;
  while (true) {
    const { destinies } = calculateDay(date);
    for (const user of ZODIACS) {
      const bodyFeatures = new Float64Array(BODY_COLUMNS);
      const houseFeatures = new Float64Array(HOUSE_COLUMNS);
      const aspectFeatures = new Float64Array(ASPECT_COLUMNS);

      for (const factor of destinies[user].factors) {
        const parts = factor.id.split(":");
        const family = parts[0];
        if (family === "house") {
          const body = parts[1] as AstroBody;
          const sign = parts[2] as ZodiacSign;
          const bodyIndex = ASTRO_BODIES.indexOf(body);
          const houseIndex = houseFor(user, sign) - 1;
          if (bodyIndex < 0 || houseIndex < 0 || houseIndex >= 12) throw new Error(`bad house factor ${factor.id}`);
          addDomainVector(bodyFeatures, bodyIndex * DOMAINS.length, factor.contributions, 1);
          addDomainVector(houseFeatures, houseIndex * DOMAINS.length, factor.contributions, 1);
        } else if (family === "sun-sign" || family === "retrograde") {
          const body = parts[1] as AstroBody;
          const bodyIndex = ASTRO_BODIES.indexOf(body);
          if (bodyIndex < 0) throw new Error(`bad body factor ${factor.id}`);
          addDomainVector(bodyFeatures, bodyIndex * DOMAINS.length, factor.contributions, 1);
        } else if (family === "aspect") {
          const first = parts[1] as AstroBody;
          const second = parts[2] as AstroBody;
          const aspectName = parts[3] as typeof ASPECT_NAMES[number];
          const firstIndex = ASTRO_BODIES.indexOf(first);
          const secondIndex = ASTRO_BODIES.indexOf(second);
          const aspectIndex = ASPECT_NAMES.indexOf(aspectName);
          if (firstIndex < 0 || secondIndex < 0 || aspectIndex < 0) throw new Error(`bad aspect factor ${factor.id}`);
          // Split an aspect equally across its two planets. Summing all body channels therefore exactly
          // reconstructs the engine's current domain score, while each planet can later receive its own
          // domain-specific adjustment.
          addDomainVector(bodyFeatures, firstIndex * DOMAINS.length, factor.contributions, 0.5);
          addDomainVector(bodyFeatures, secondIndex * DOMAINS.length, factor.contributions, 0.5);
          addDomainVector(aspectFeatures, aspectIndex * DOMAINS.length, factor.contributions, 1);
        } else {
          throw new Error(`unsupported astrology factor family: ${factor.id}`);
        }
      }

      values.set(bodyFeatures, cursor);
      cursor += BODY_COLUMNS;
      values.set(houseFeatures, cursor);
      cursor += HOUSE_COLUMNS;
      values.set(aspectFeatures, cursor);
      cursor += ASPECT_COLUMNS;
      rows++;
    }
    if (date === end) break;
    date = addDays(date, 1);
  }
}

if (rows !== expectedRows || cursor !== values.length) {
  throw new Error(`feature size mismatch rows=${rows}/${expectedRows} cursor=${cursor}/${values.length}`);
}

await Deno.writeFile(`weight-v3-features-${shard}.bin`, new Uint8Array(values.buffer));
await Deno.writeTextFile(
  `weight-v3-features-${shard}.json`,
  JSON.stringify({
    version: 3,
    shard,
    shardCount,
    rows,
    columns: COLUMNS,
    bodyColumns: BODY_COLUMNS,
    houseColumns: HOUSE_COLUMNS,
    aspectColumns: ASPECT_COLUMNS,
    bodies: ASTRO_BODIES,
    houses: Array.from({ length: 12 }, (_, i) => i + 1),
    aspects: ASPECT_NAMES,
    domains: DOMAINS,
    note: "Body channels reconstruct the exact current score. House and aspect channels are overlapping adjustment channels used only by the calibration search.",
  }, null, 2),
);
console.log(JSON.stringify({ shard, rows, columns: COLUMNS, floats: values.length }));

function addDomainVector(
  target: Float64Array,
  offset: number,
  contributions: Record<FortuneDomain, number>,
  scale: number,
) {
  for (let d = 0; d < DOMAINS.length; d++) {
    target[offset + d] += contributions[DOMAINS[d]] * scale;
  }
}

function houseFor(user: ZodiacSign, transit: ZodiacSign): number {
  return mod(ZODIACS.indexOf(transit) - ZODIACS.indexOf(user), 12) + 1;
}

function mod(value: number, divisor: number): number {
  return ((value % divisor) + divisor) % divisor;
}

function daysInYear(year: number): number {
  return (Date.UTC(year + 1, 0, 1) - Date.UTC(year, 0, 1)) / 86_400_000;
}

function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + days));
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, "0")}-${String(dt.getUTCDate()).padStart(2, "0")}`;
}
