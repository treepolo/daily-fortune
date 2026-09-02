import {
  DOMAINS,
  ZODIACS,
  calculateDay,
  grade,
  type FortuneDomain,
  type FortuneGrade,
  type ZodiacSign,
} from "../supabase/functions/_shared/astrology_v1.ts";

const shardIndex = Number(Deno.args[0]);
const shardCount = Number(Deno.args[1]);
if (!Number.isInteger(shardIndex) || !Number.isInteger(shardCount) || shardIndex < 0 || shardIndex >= shardCount) {
  throw new Error("Usage: deno run ... astrology_calibration_shard.ts <shardIndex> <shardCount>");
}

const GRADES: FortuneGrade[] = ["DAI_JI", "JI", "XIAO_JI", "PING", "XIAO_XIONG", "XIONG", "DAI_XIONG"];
type MetricKey = "OVERALL" | FortuneDomain;
const METRICS: MetricKey[] = ["OVERALL", ...DOMAINS];

type Extreme = { score: number; date: string; zodiac: ZodiacSign };
type MetricAccumulator = {
  count: number;
  sum: number;
  min: Extreme | null;
  max: Extreme | null;
  gradeCounts: Record<FortuneGrade, number>;
  histogram01: Record<string, number>;
};

type ShardResult = {
  shardIndex: number;
  shardCount: number;
  years: number[];
  calendarDays: number;
  destinyCount: number;
  histogramResolution: number;
  metrics: Record<MetricKey, MetricAccumulator>;
  overallGradeCountsByZodiac: Record<ZodiacSign, Record<FortuneGrade, number>>;
};

function emptyGradeCounts(): Record<FortuneGrade, number> {
  return Object.fromEntries(GRADES.map((value) => [value, 0])) as Record<FortuneGrade, number>;
}

function emptyMetric(): MetricAccumulator {
  return { count: 0, sum: 0, min: null, max: null, gradeCounts: emptyGradeCounts(), histogram01: {} };
}

const result: ShardResult = {
  shardIndex,
  shardCount,
  years: [],
  calendarDays: 0,
  destinyCount: 0,
  histogramResolution: 0.01,
  metrics: Object.fromEntries(METRICS.map((metric) => [metric, emptyMetric()])) as Record<MetricKey, MetricAccumulator>,
  overallGradeCountsByZodiac: Object.fromEntries(
    ZODIACS.map((zodiac) => [zodiac, emptyGradeCounts()]),
  ) as Record<ZodiacSign, Record<FortuneGrade, number>>,
};

function observe(metric: MetricKey, score: number, date: string, zodiac: ZodiacSign) {
  const acc = result.metrics[metric];
  const g = grade(score);
  acc.count += 1;
  acc.sum += score;
  acc.gradeCounts[g] += 1;
  if (acc.min == null || score < acc.min.score) acc.min = { score, date, zodiac };
  if (acc.max == null || score > acc.max.score) acc.max = { score, date, zodiac };
  const bucket = String(Math.round(score * 100));
  acc.histogram01[bucket] = (acc.histogram01[bucket] ?? 0) + 1;
}

for (let year = 1900 + shardIndex; year <= 2100; year += shardCount) {
  result.years.push(year);
  let cursor = new Date(Date.UTC(year, 0, 1));
  const end = Date.UTC(year + 1, 0, 1);
  while (cursor.getTime() < end) {
    const date = cursor.toISOString().slice(0, 10);
    const { destinies } = calculateDay(date);
    result.calendarDays += 1;

    for (const zodiac of ZODIACS) {
      const destiny = destinies[zodiac];
      observe("OVERALL", destiny.overallScore, date, zodiac);
      result.overallGradeCountsByZodiac[zodiac][destiny.overallGrade] += 1;
      for (const domain of DOMAINS) observe(domain, destiny.domainScores[domain], date, zodiac);
      result.destinyCount += 1;
    }

    cursor = new Date(cursor.getTime() + 86_400_000);
  }
  console.log(`shard ${shardIndex}: completed ${year}`);
}

const output = `calibration-shard-${shardIndex}.json`;
await Deno.writeTextFile(output, JSON.stringify(result));
console.log(`wrote ${output}: ${result.calendarDays} days, ${result.destinyCount} destinies`);
