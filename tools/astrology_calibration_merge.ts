import { DOMAINS, ZODIACS, type FortuneDomain, type FortuneGrade, type ZodiacSign } from "../supabase/functions/_shared/astrology_v1.ts";

const GRADES: FortuneGrade[] = ["DAI_JI", "JI", "XIAO_JI", "PING", "XIAO_XIONG", "XIONG", "DAI_XIONG"];
const GRADE_LABEL: Record<FortuneGrade, string> = {
  DAI_JI: "大吉", JI: "吉", XIAO_JI: "小吉", PING: "平", XIAO_XIONG: "小凶", XIONG: "凶", DAI_XIONG: "大凶",
};
type MetricKey = "OVERALL" | FortuneDomain;
const METRICS: MetricKey[] = ["OVERALL", ...DOMAINS];
const METRIC_LABEL: Record<MetricKey, string> = {
  OVERALL: "總運勢", WEALTH: "財運", LOVE: "戀愛", WORK_STUDY: "工作／學業", RELATIONSHIPS: "人際", HEALTH: "健康",
};
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

function gradeCounts(): Record<FortuneGrade, number> {
  return Object.fromEntries(GRADES.map((g) => [g, 0])) as Record<FortuneGrade, number>;
}
function metric(): MetricAccumulator {
  return { count: 0, sum: 0, min: null, max: null, gradeCounts: gradeCounts(), histogram01: {} };
}

const files: string[] = [];
for await (const entry of Deno.readDir(".")) {
  if (entry.isFile && /^calibration-shard-\d+\.json$/.test(entry.name)) files.push(entry.name);
}
files.sort((a, b) => Number(a.match(/\d+/)?.[0]) - Number(b.match(/\d+/)?.[0]));
if (files.length === 0) throw new Error("No calibration shard files found");

const merged = {
  range: { start: "1900-01-01", end: "2100-12-31" },
  calendarDays: 0,
  destinyCount: 0,
  histogramResolution: 0.01,
  shardCount: files.length,
  metrics: Object.fromEntries(METRICS.map((key) => [key, metric()])) as Record<MetricKey, MetricAccumulator>,
  overallGradeCountsByZodiac: Object.fromEntries(ZODIACS.map((z) => [z, gradeCounts()])) as Record<ZodiacSign, Record<FortuneGrade, number>>,
};

for (const file of files) {
  const shard = JSON.parse(await Deno.readTextFile(file)) as ShardResult;
  merged.calendarDays += shard.calendarDays;
  merged.destinyCount += shard.destinyCount;
  for (const key of METRICS) {
    const from = shard.metrics[key];
    const to = merged.metrics[key];
    to.count += from.count;
    to.sum += from.sum;
    if (from.min && (!to.min || from.min.score < to.min.score)) to.min = from.min;
    if (from.max && (!to.max || from.max.score > to.max.score)) to.max = from.max;
    for (const g of GRADES) to.gradeCounts[g] += from.gradeCounts[g];
    for (const [bucket, count] of Object.entries(from.histogram01)) {
      to.histogram01[bucket] = (to.histogram01[bucket] ?? 0) + count;
    }
  }
  for (const z of ZODIACS) for (const g of GRADES) merged.overallGradeCountsByZodiac[z][g] += shard.overallGradeCountsByZodiac[z][g];
}

function quantile(acc: MetricAccumulator, q: number): number {
  const target = Math.max(1, Math.ceil(acc.count * q));
  let running = 0;
  const buckets = Object.entries(acc.histogram01)
    .map(([bucket, count]) => [Number(bucket), count] as const)
    .sort((a, b) => a[0] - b[0]);
  for (const [bucket, count] of buckets) {
    running += count;
    if (running >= target) return bucket / 100;
  }
  return buckets[buckets.length - 1][0] / 100;
}

const percentilePoints = [0.001, 0.01, 0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99, 0.999];
const report = {
  engine: "astrology-v1.0.0",
  ephemeris: "astronomy-engine-2.1.19",
  range: merged.range,
  calendarDays: merged.calendarDays,
  zodiacSignsPerDay: 12,
  destinyCount: merged.destinyCount,
  histogramResolution: merged.histogramResolution,
  currentGradeThresholds: {
    DAI_JI: ">= 10.0", JI: "5.5..9.999", XIAO_JI: "1.8..5.499", PING: "(-1.8)..1.8", XIAO_XIONG: "(-5.5)..(-1.8]", XIONG: "(-10)..(-5.5]", DAI_XIONG: "<= -10.0",
  },
  metrics: Object.fromEntries(METRICS.map((key) => {
    const acc = merged.metrics[key];
    return [key, {
      count: acc.count,
      mean: acc.sum / acc.count,
      min: acc.min,
      max: acc.max,
      percentiles: Object.fromEntries(percentilePoints.map((q) => [`p${q * 100}`, quantile(acc, q)])),
      gradeCounts: acc.gradeCounts,
      gradePercentages: Object.fromEntries(GRADES.map((g) => [g, acc.gradeCounts[g] * 100 / acc.count])),
    }];
  })),
  overallGradePercentagesByZodiac: Object.fromEntries(ZODIACS.map((z) => {
    const counts = merged.overallGradeCountsByZodiac[z];
    const total = GRADES.reduce((sum, g) => sum + counts[g], 0);
    return [z, Object.fromEntries(GRADES.map((g) => [g, counts[g] * 100 / total]))];
  })),
};

await Deno.writeTextFile("calibration-report.json", JSON.stringify(report, null, 2));

const lines: string[] = [];
lines.push("# Astrology Engine v1 — 1900–2100 full-distribution calibration");
lines.push("");
lines.push(`- Calendar range: ${report.range.start} through ${report.range.end}`);
lines.push(`- Every calendar day evaluated: ${report.calendarDays.toLocaleString("en-US")}`);
lines.push(`- Zodiac destinies evaluated: ${report.destinyCount.toLocaleString("en-US")} (${report.calendarDays.toLocaleString("en-US")} days × 12 signs)`);
lines.push(`- Percentiles use a ${report.histogramResolution.toFixed(2)}-point score histogram; min/max and grade counts are exact.`);
lines.push("");
lines.push("## Score distribution");
lines.push("");
lines.push("| Metric | Min | p1 | p5 | p25 | Median | p75 | p95 | p99 | Max | Mean |");
lines.push("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
for (const key of METRICS) {
  const data = report.metrics[key] as any;
  lines.push(`| ${METRIC_LABEL[key]} | ${data.min.score.toFixed(2)} | ${data.percentiles.p1.toFixed(2)} | ${data.percentiles.p5.toFixed(2)} | ${data.percentiles.p25.toFixed(2)} | ${data.percentiles.p50.toFixed(2)} | ${data.percentiles.p75.toFixed(2)} | ${data.percentiles.p95.toFixed(2)} | ${data.percentiles.p99.toFixed(2)} | ${data.max.score.toFixed(2)} | ${data.mean.toFixed(2)} |`);
}
lines.push("");
lines.push("## Current seven-grade frequencies");
lines.push("");
lines.push(`| Metric | ${GRADES.map((g) => GRADE_LABEL[g]).join(" | ")} |`);
lines.push(`|---|${GRADES.map(() => "---:").join("|")}|`);
for (const key of METRICS) {
  const data = report.metrics[key] as any;
  lines.push(`| ${METRIC_LABEL[key]} | ${GRADES.map((g) => `${data.gradePercentages[g].toFixed(4)}%`).join(" | ")} |`);
}
lines.push("");
lines.push("## Exact observed extremes");
lines.push("");
for (const key of METRICS) {
  const data = report.metrics[key] as any;
  lines.push(`- ${METRIC_LABEL[key]} min: ${data.min.score.toFixed(6)} on ${data.min.date} / ${data.min.zodiac}; max: ${data.max.score.toFixed(6)} on ${data.max.date} / ${data.max.zodiac}.`);
}
lines.push("");
lines.push("No grade thresholds are changed by this calibration job. The report exists to diagnose the current scoring scale before any policy decision.");
await Deno.writeTextFile("calibration-report.md", lines.join("\n"));
console.log(lines.join("\n"));
