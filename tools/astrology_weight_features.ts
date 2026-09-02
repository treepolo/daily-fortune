import { DOMAINS, ZODIACS, calculateDay } from "../supabase/functions/_shared/astrology_v1.ts";

const shard = Number(Deno.args[0] ?? "0");
const shardCount = Number(Deno.args[1] ?? "1");
if (!Number.isInteger(shard) || !Number.isInteger(shardCount) || shard < 0 || shard >= shardCount) {
  throw new Error("usage: astrology_weight_features.ts <shard> <shardCount>");
}

const FAMILY = ["house:", "sun-sign:", "retrograde:", "aspect:"] as const;
const values: number[] = [];
let rows = 0;

for (let year = 1900; year <= 2100; year++) {
  if ((year - 1900) % shardCount !== shard) continue;
  let date = `${year}-01-01`;
  const end = `${year}-12-31`;
  while (true) {
    const { destinies } = calculateDay(date);
    for (const zodiac of ZODIACS) {
      const factors = destinies[zodiac].factors;
      for (const prefix of FAMILY) {
        for (const domain of DOMAINS) {
          let sum = 0;
          for (const factor of factors) {
            if (factor.id.startsWith(prefix)) sum += factor.contributions[domain];
          }
          values.push(sum);
        }
      }
      rows++;
    }
    if (date === end) break;
    date = addDays(date, 1);
  }
}

const floats = Float32Array.from(values);
await Deno.writeFile(`weight-features-${shard}.bin`, new Uint8Array(floats.buffer));
await Deno.writeTextFile(
  `weight-features-${shard}.json`,
  JSON.stringify({ shard, shardCount, rows, columns: 20, family: FAMILY, domains: DOMAINS }, null, 2),
);
console.log(JSON.stringify({ shard, rows, floats: floats.length }));

function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + days));
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, "0")}-${String(dt.getUTCDate()).padStart(2, "0")}`;
}
