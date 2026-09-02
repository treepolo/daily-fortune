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
type JointKey =
  | "DOMAIN_DAI_JI_AND_DAI_XIONG"
  | "OVERALL_DAI_XIONG_WITH_DOMAIN_DAI_JI"
  | "OVERALL_DAI_JI_WITH_DOMAIN_DAI_XIONG";
const JOINT_KEYS: JointKey[] = [
  "DOMAIN_DAI_JI_AND_DAI_XIONG",
  "OVERALL_DAI_XIONG_WITH_DOMAIN_DAI_JI",
  "OVERALL_DAI_JI_WITH_DOMAIN_DAI_XIONG",
];

type Extreme = { score: number; date: string; zodiac: ZodiacSign };
type MetricAccumulator = {
  count: number;
  sum: number;
  min: Extreme | null;
  max: Extreme | null;
  gradeCounts: Record<FortuneGrade, number>;
  histogram01: Record<string, number>;
};
type JointExample = {
  date: string;
  zodiac: ZodiacSign;
  overallScore: number;
  overallGrade: FortuneGrade;
  domainScores: Record<FortuneDomain, number>;
  domainGrades: Record<FortuneDomain, FortuneGrade>;
};
type JointAccumulator = {
  count: number;
  example: JointExample | null;
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
  joint: Record<JointKey, JointAccumulator>;
  jointCountsByZodiac: Record<ZodiacSign, Record<JointKey, number>>;
};

function emptyGradeCounts(): Record<FortuneGrade, number> {
  return Object.fromEntries(GRADES.map((value) => [value, 0])) as Record<FortuneGrade, number>;
}

function emptyMetric(): MetricAccumulator {
  return { count: 0, sum: 0, min: null, max: null, gradeCounts: emptyGradeCounts(), histogram01: {} };
}

function emptyJointCounts(): Record<JointKey, number> {
  return Object.fromEntries(JOINT_KEYS.map((key) => [key, 0])) as Record<JointKey, number>;
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
  joint: Object.fromEntries(JOINT_KEYS.map((key) => [key, { count: 0, example: null }])) as Record<JointKey, JointAccumulator>,
  jointCountsByZodiac: Object.fromEntries(
    ZODIACS.map((zodiac) => [zodiac, emptyJointCounts()]),
  ) as Record<ZodiacSign, Record<JointKey, number>>,
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

function observeJoint(key: JointKey, example: JointExample) {
  const acc = result.joint[key];
  acc.count += 1;
  if (acc.example == null) acc.example = example;
  result.jointCountsByZodiac[example.zodiac][key] += 1;
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

      const domainGrades = DOMAINS.map((domain) => destiny.domainGrades[domain]);
      const hasDomainDaiJi = domainGrades.includes("DAI_JI");
      const hasDomainDaiXiong = domainGrades.includes("DAI_XIONG");
      const example: JointExample = {
        date,
        zodiac,
        overallScore: destiny.overallScore,
        overallGrade: destiny.overallGrade,
        domainScores: destiny.domainScores,
        domainGrades: destiny.domainGrades,
      };
      if (hasDomainDaiJi && hasDomainDaiXiong) observeJoint("DOMAIN_DAI_JI_AND_DAI_XIONG", example);
      if (destiny.overallGrade === "DAI_XIONG" && hasDomainDaiJi) observeJoint("OVERALL_DAI_XIONG_WITH_DOMAIN_DAI_JI", example);
      if (destiny.overallGrade === "DAI_JI" && hasDomainDaiXiong) observeJoint("OVERALL_DAI_JI_WITH_DOMAIN_DAI_XIONG", example);

      result.destinyCount += 1;
    }

    cursor = new Date(cursor.getTime() + 86_400_000);
  }
  console.log(`shard ${shardIndex}: completed ${year}`);
}

const output = `calibration-shard-${shardIndex}.json`;
await Deno.writeTextFile(output, JSON.stringify(result));
console.log(`wrote ${output}: ${result.calendarDays} days, ${result.destinyCount} destinies`);
