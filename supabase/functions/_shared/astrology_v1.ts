import { Body, Ecliptic, GeoVector } from "npm:astronomy-engine@2.1.19";
import {
  DOMAIN_GRADE_THRESHOLDS,
  OVERALL_GRADE_THRESHOLDS,
  aspectFactorMultiplier,
  bodyDomainMultiplier,
  houseFactorMultiplier,
  type CalibrationAspect,
  type GradeThresholds,
} from "./astrology_calibration_v4.ts";

export const ASTROLOGY_ENGINE_VERSION = "astrology-v1.1.0";
export const EPHEMERIS_VERSION = "astronomy-engine-2.1.19";
export const TAIPEI_ZONE = "Asia/Taipei";

export const ZODIACS = [
  "ARIES", "TAURUS", "GEMINI", "CANCER", "LEO", "VIRGO",
  "LIBRA", "SCORPIO", "SAGITTARIUS", "CAPRICORN", "AQUARIUS", "PISCES",
] as const;
export type ZodiacSign = typeof ZODIACS[number];

export const DOMAINS = ["WEALTH", "LOVE", "WORK_STUDY", "RELATIONSHIPS", "HEALTH"] as const;
export type FortuneDomain = typeof DOMAINS[number];

export const ASTRO_BODIES = [
  "SUN", "MOON", "MERCURY", "VENUS", "MARS",
  "JUPITER", "SATURN", "URANUS", "NEPTUNE", "PLUTO",
] as const;
export type AstroBody = typeof ASTRO_BODIES[number];

export type FortuneGrade = "DAI_JI" | "JI" | "XIAO_JI" | "PING" | "XIAO_XIONG" | "XIONG" | "DAI_XIONG";

type NumberMap = Record<FortuneDomain, number>;

type AstroSample = { instant: Date; longitudes: Record<AstroBody, number> };
type SignIngress = { body: AstroBody; from: ZodiacSign; to: ZodiacSign; nearTime: Date };
type BodyDaySummary = {
  body: AstroBody;
  signFractions: Partial<Record<ZodiacSign, number>>;
  averageSpeedDegPerDay: number;
  retrogradeFraction: number;
  directionChanged: boolean;
  ingresses: SignIngress[];
};
type AspectDefinition = {
  name: string;
  label: string;
  angle: number;
  maxOrb: number;
  basePolarity: number | null;
  importance: number;
};
type AspectHit = {
  first: AstroBody;
  second: AstroBody;
  aspect: AspectDefinition;
  closestTime: Date;
  orbDegrees: number;
};
export type AstronomyDayData = {
  date: string;
  samples: AstroSample[];
  bodies: Record<AstroBody, BodyDaySummary>;
  aspects: AspectHit[];
};
export type AstrologyFactor = {
  id: string;
  title: string;
  evidence: string;
  contributions: NumberMap;
};
export type AstrologyDestiny = {
  zodiac: ZodiacSign;
  overallGrade: FortuneGrade;
  overallScore: number;
  overallExplanation: string;
  domainScores: NumberMap;
  domainGrades: Record<FortuneDomain, FortuneGrade>;
  domainExplanations: Record<FortuneDomain, string>;
  factors: AstrologyFactor[];
};
export type ParallelSkyResult = {
  originalDate: string;
  sourceDate: string;
  originalSunLongitude: number;
  alteredSunLongitude: number;
  sunLongitudeDifference: number;
  astronomy: AstronomyDayData;
  destiny: AstrologyDestiny;
};

const ENGINE_BODY: Record<AstroBody, Body> = {
  SUN: Body.Sun,
  MOON: Body.Moon,
  MERCURY: Body.Mercury,
  VENUS: Body.Venus,
  MARS: Body.Mars,
  JUPITER: Body.Jupiter,
  SATURN: Body.Saturn,
  URANUS: Body.Uranus,
  NEPTUNE: Body.Neptune,
  PLUTO: Body.Pluto,
};

const BODY_LABEL: Record<AstroBody, string> = {
  SUN: "太陽", MOON: "月亮", MERCURY: "水星", VENUS: "金星", MARS: "火星",
  JUPITER: "木星", SATURN: "土星", URANUS: "天王星", NEPTUNE: "海王星", PLUTO: "冥王星",
};
const ZODIAC_LABEL: Record<ZodiacSign, string> = {
  ARIES: "牡羊座", TAURUS: "金牛座", GEMINI: "雙子座", CANCER: "巨蟹座",
  LEO: "獅子座", VIRGO: "處女座", LIBRA: "天秤座", SCORPIO: "天蠍座",
  SAGITTARIUS: "射手座", CAPRICORN: "摩羯座", AQUARIUS: "水瓶座", PISCES: "雙魚座",
};
const DOMAIN_LABEL: Record<FortuneDomain, string> = {
  WEALTH: "財運", LOVE: "戀愛", WORK_STUDY: "工作／學業", RELATIONSHIPS: "人際", HEALTH: "健康",
};

const ASPECTS: AspectDefinition[] = [
  { name: "CONJUNCTION", label: "合相", angle: 0, maxOrb: 1.5, basePolarity: null, importance: 1.0 },
  { name: "SEXTILE", label: "六合", angle: 60, maxOrb: 1.0, basePolarity: 0.45, importance: 0.60 },
  { name: "SQUARE", label: "刑相", angle: 90, maxOrb: 1.5, basePolarity: -0.75, importance: 0.90 },
  { name: "TRINE", label: "拱相", angle: 120, maxOrb: 1.2, basePolarity: 0.65, importance: 0.75 },
  { name: "OPPOSITION", label: "對分", angle: 180, maxOrb: 1.5, basePolarity: -0.70, importance: 0.95 },
];

const SAMPLE_MINUTES = 15;
const INTERVAL_COUNT = 96;
const MOTION_EPSILON = 0.002;

export function analyzeDay(date: string): AstronomyDayData {
  const start = taipeiInstant(date, 0, 0);
  const samples: AstroSample[] = Array.from({ length: INTERVAL_COUNT + 1 }, (_, index) => {
    const instant = new Date(start.getTime() + index * SAMPLE_MINUTES * 60_000);
    const longitudes = Object.fromEntries(
      ASTRO_BODIES.map((body) => [body, longitude(body, instant)]),
    ) as Record<AstroBody, number>;
    return { instant, longitudes };
  });

  const bodies = Object.fromEntries(
    ASTRO_BODIES.map((body) => [body, summarizeBody(body, samples)]),
  ) as Record<AstroBody, BodyDaySummary>;

  return { date, samples, bodies, aspects: detectAspects(samples) };
}

export function longitude(body: AstroBody, instant: Date): number {
  const vector = GeoVector(ENGINE_BODY[body], instant, true);
  return normalize(Ecliptic(vector).elon);
}

function summarizeBody(body: AstroBody, samples: AstroSample[]): BodyDaySummary {
  const counts = new Map<ZodiacSign, number>();
  for (const sample of samples.slice(0, -1)) {
    const sign = zodiac(sample.longitudes[body]);
    counts.set(sign, (counts.get(sign) ?? 0) + 1);
  }
  const signFractions: Partial<Record<ZodiacSign, number>> = {};
  for (const sign of ZODIACS) {
    const count = counts.get(sign) ?? 0;
    if (count > 0) signFractions[sign] = count / INTERVAL_COUNT;
  }

  const indices = [0, 24, 48, 72, 96];
  const deltas = indices.slice(0, -1).map((a, i) =>
    signedAngularDelta(samples[a].longitudes[body], samples[indices[i + 1]].longitudes[body])
  );
  const retrogradeIntervals = body === "SUN" || body === "MOON" ? 0 : deltas.filter((x) => x < -MOTION_EPSILON).length;
  const directIntervals = deltas.filter((x) => x > MOTION_EPSILON).length;
  const directionChanged = body !== "SUN" && body !== "MOON" && retrogradeIntervals > 0 && directIntervals > 0;

  const ingresses: SignIngress[] = [];
  for (let i = 0; i < samples.length - 1; i++) {
    const from = zodiac(samples[i].longitudes[body]);
    const to = zodiac(samples[i + 1].longitudes[body]);
    if (from !== to) ingresses.push({ body, from, to, nearTime: samples[i + 1].instant });
  }

  return {
    body,
    signFractions,
    averageSpeedDegPerDay: deltas.reduce((a, b) => a + b, 0),
    retrogradeFraction: retrogradeIntervals / deltas.length,
    directionChanged,
    ingresses,
  };
}

function detectAspects(samples: AstroSample[]): AspectHit[] {
  const hits: AspectHit[] = [];
  for (let i = 0; i < ASTRO_BODIES.length; i++) {
    for (let j = i + 1; j < ASTRO_BODIES.length; j++) {
      const first = ASTRO_BODIES[i];
      const second = ASTRO_BODIES[j];
      for (const aspect of ASPECTS) {
        let closest = samples[0];
        let best = Number.POSITIVE_INFINITY;
        for (const sample of samples) {
          const orb = aspectOrb(sample.longitudes[first], sample.longitudes[second], aspect.angle);
          if (orb < best) {
            best = orb;
            closest = sample;
          }
        }
        if (best <= aspect.maxOrb) hits.push({ first, second, aspect, closestTime: closest.instant, orbDegrees: best });
      }
    }
  }
  return hits;
}

export function calculateDay(date: string): { astronomy: AstronomyDayData; destinies: Record<ZodiacSign, AstrologyDestiny> } {
  const astronomy = analyzeDay(date);
  const destinies = Object.fromEntries(
    ZODIACS.map((sign) => [sign, calculate(sign, astronomy)]),
  ) as Record<ZodiacSign, AstrologyDestiny>;
  return { astronomy, destinies };
}

export function calculate(zodiacSign: ZodiacSign, astronomy: AstronomyDayData): AstrologyDestiny {
  const factors = buildFactors(zodiacSign, astronomy);
  const domainScores = Object.fromEntries(
    DOMAINS.map((domain) => [domain, factors.reduce((sum, factor) => sum + factor.contributions[domain], 0)]),
  ) as NumberMap;
  const overallScore = DOMAINS.reduce((sum, domain) => sum + domainScores[domain], 0) / DOMAINS.length;
  const domainGrades = Object.fromEntries(DOMAINS.map((domain) => [domain, gradeDomain(domainScores[domain], domain)])) as Record<FortuneDomain, FortuneGrade>;
  const domainExplanations = Object.fromEntries(
    DOMAINS.map((domain) => [domain, domainExplanation(domain, domainScores[domain], factors)]),
  ) as Record<FortuneDomain, string>;

  return {
    zodiac: zodiacSign,
    overallGrade: grade(overallScore),
    overallScore,
    overallExplanation: overallExplanation(overallScore, domainScores, factors),
    domainScores,
    domainGrades,
    domainExplanations,
    factors,
  };
}

function buildFactors(user: ZodiacSign, astronomy: AstronomyDayData): AstrologyFactor[] {
  const factors: AstrologyFactor[] = [];
  for (const summary of Object.values(astronomy.bodies)) {
    for (const [signText, fractionValue] of Object.entries(summary.signFractions)) {
      const sign = signText as ZodiacSign;
      const fraction = fractionValue as number;
      const house = houseFor(user, sign);
      const dignity = dignityMultiplier(summary.body, sign);
      addIfMeaningful(factors, factor(
        `house:${summary.body}:${sign}`,
        `${BODY_LABEL[summary.body]}位於第${house}宮`,
        `${BODY_LABEL[summary.body]}於今日約 ${Math.trunc(fraction * 100)}% 時間位於${ZODIAC_LABEL[sign]}（太陽星座整宮制第${house}宮）`,
        (domain) => bodyValence(summary.body) * affinity(summary.body, domain) * houseWeight(house, domain) * dignity * fraction * 8,
      ), (domain) => houseFactorMultiplier(summary.body, house, domain));

      const relation = signRelation(user, sign);
      if (relation) {
        addIfMeaningful(factors, factor(
          `sun-sign:${summary.body}:${sign}:${relation.label}`,
          `${BODY_LABEL[summary.body]}與${ZODIAC_LABEL[user]}形成${relation.label}`,
          `${BODY_LABEL[summary.body]}位於${ZODIAC_LABEL[sign]}，以${ZODIAC_LABEL[user]}為太陽星座時形成星座級${relation.label}；不假設出生太陽的精確度數`,
          (domain) => relation.polarity(summary.body) * affinity(summary.body, domain) * dignity * fraction * 5,
        ), (domain) => bodyDomainMultiplier(summary.body, domain));
      }
    }

    if (summary.retrogradeFraction >= 0.5 && summary.body !== "SUN" && summary.body !== "MOON") {
      const dominantSign = Object.entries(summary.signFractions).sort((a, b) => (b[1] ?? 0) - (a[1] ?? 0))[0][0] as ZodiacSign;
      const house = houseFor(user, dominantSign);
      const stationBoost = summary.directionChanged ? 1.35 : 1.0;
      addIfMeaningful(factors, factor(
        `retrograde:${summary.body}`,
        `${BODY_LABEL[summary.body]}逆行`,
        `${BODY_LABEL[summary.body]}在四個六小時運動區段中約 ${Math.trunc(summary.retrogradeFraction * 100)}% 呈逆行${summary.directionChanged ? "，且當日偵測到運動方向切換" : ""}`,
        (domain) => -3 * retrogradeSensitivity(summary.body) * affinity(summary.body, domain) *
          (0.5 + 0.5 * clamp(houseWeight(house, domain), 0, 1)) * stationBoost,
      ), (domain) => bodyDomainMultiplier(summary.body, domain));
    }
  }

  const sampleByMillis = new Map(astronomy.samples.map((sample) => [sample.instant.getTime(), sample]));
  for (const hit of astronomy.aspects) {
    const sample = sampleByMillis.get(hit.closestTime.getTime());
    if (!sample) throw new Error("Aspect sample missing");
    const signA = zodiac(sample.longitudes[hit.first]);
    const signB = zodiac(sample.longitudes[hit.second]);
    const houseA = houseFor(user, signA);
    const houseB = houseFor(user, signB);
    const strength = clamp(1 - hit.orbDegrees / hit.aspect.maxOrb, 0, 1);
    const dignity = (dignityMultiplier(hit.first, signA) + dignityMultiplier(hit.second, signB)) / 2;
    const polarity = hit.aspect.basePolarity ?? clamp(bodyValence(hit.first) + bodyValence(hit.second), -1, 1);
    addIfMeaningful(factors, factor(
      `aspect:${hit.first}:${hit.second}:${hit.aspect.name}`,
      `${BODY_LABEL[hit.first]}與${BODY_LABEL[hit.second]}${hit.aspect.label}`,
      `最近於 ${taipeiHm(hit.closestTime)}，與${Math.trunc(hit.aspect.angle)}°相差 ${hit.orbDegrees.toFixed(2)}°；分別落第${houseA}、${houseB}宮`,
      (domain) => {
        const avgAffinity = (affinity(hit.first, domain) + affinity(hit.second, domain)) / 2;
        const houseRelevance = 0.6 + 0.4 * clamp(Math.max(houseWeight(houseA, domain), houseWeight(houseB, domain)), 0, 1);
        return polarity * hit.aspect.importance * strength * avgAffinity * houseRelevance * dignity * 10;
      },
    ), (domain) => aspectFactorMultiplier(hit.first, hit.second, hit.aspect.name as CalibrationAspect, domain));
  }
  return factors;
}

function factor(id: string, title: string, evidence: string, value: (domain: FortuneDomain) => number): AstrologyFactor {
  return {
    id,
    title,
    evidence,
    contributions: Object.fromEntries(DOMAINS.map((domain) => [domain, value(domain)])) as NumberMap,
  };
}

function addIfMeaningful(
  list: AstrologyFactor[],
  item: AstrologyFactor,
  multiplier: (domain: FortuneDomain) => number = () => 1,
) {
  if (!DOMAINS.some((domain) => Math.abs(item.contributions[domain]) >= 0.01)) return;
  list.push({
    ...item,
    contributions: Object.fromEntries(
      DOMAINS.map((domain) => [domain, item.contributions[domain] * multiplier(domain)]),
    ) as NumberMap,
  });
}

export function grade(score: number): FortuneGrade {
  return gradeWithThresholds(score, OVERALL_GRADE_THRESHOLDS);
}

function gradeDomain(score: number, domain: FortuneDomain): FortuneGrade {
  return gradeWithThresholds(score, DOMAIN_GRADE_THRESHOLDS[domain]);
}

function gradeWithThresholds(score: number, thresholds: GradeThresholds): FortuneGrade {
  if (score >= thresholds[5]) return "DAI_JI";
  if (score >= thresholds[4]) return "JI";
  if (score >= thresholds[3]) return "XIAO_JI";
  if (score >= thresholds[2]) return "PING";
  if (score >= thresholds[1]) return "XIAO_XIONG";
  if (score >= thresholds[0]) return "XIONG";
  return "DAI_XIONG";
}

function domainExplanation(domain: FortuneDomain, score: number, factors: AstrologyFactor[]): string {
  const ranked = factors.map((f) => ({ f, value: f.contributions[domain] }));
  const positive = ranked.filter((x) => x.value > 0.05).sort((a, b) => b.value - a.value)[0];
  const negative = ranked.filter((x) => x.value < -0.05).sort((a, b) => a.value - b.value)[0];
  let suffix: string;
  if (positive && negative) suffix = `${positive.f.title}是主要加分；${negative.f.title}形成主要壓力。`;
  else if (positive) suffix = `主要正向來源是${positive.f.title}。`;
  else if (negative) suffix = `主要壓力來源是${negative.f.title}。`;
  else suffix = "今日沒有單一高權重天象主導此領域，整體接近平衡。";
  return `${gradeLabel(gradeDomain(score, domain))}（${score.toFixed(1)}分）。${suffix}`;
}

function overallExplanation(overall: number, scores: NumberMap, factors: AstrologyFactor[]): string {
  const sorted = [...DOMAINS].sort((a, b) => scores[b] - scores[a]);
  const totals = factors.map((f) => ({ f, value: DOMAINS.reduce((sum, d) => sum + f.contributions[d], 0) }));
  const positive = totals.filter((x) => x.value > 0.1).sort((a, b) => b.value - a.value)[0];
  const negative = totals.filter((x) => x.value < -0.1).sort((a, b) => a.value - b.value)[0];
  let text = `${gradeLabel(grade(overall))}（五項平均 ${overall.toFixed(1)} 分）。相對較強的是${DOMAIN_LABEL[sorted[0]]}，較需留意${DOMAIN_LABEL[sorted[sorted.length - 1]]}。`;
  if (positive) text += `主要加分來自${positive.f.title}。`;
  if (negative) text += `主要壓力來自${negative.f.title}。`;
  return text;
}

function gradeLabel(value: FortuneGrade): string {
  return ({ DAI_JI: "大吉", JI: "吉", XIAO_JI: "小吉", PING: "平", XIAO_XIONG: "小凶", XIONG: "凶", DAI_XIONG: "大凶" } as const)[value];
}

function houseFor(user: ZodiacSign, transit: ZodiacSign): number {
  return mod(ZODIACS.indexOf(transit) - ZODIACS.indexOf(user), 12) + 1;
}

function signRelation(user: ZodiacSign, transit: ZodiacSign): { label: string; polarity: (body: AstroBody) => number } | null {
  const distance = mod(ZODIACS.indexOf(transit) - ZODIACS.indexOf(user), 12);
  if (distance === 0) return { label: "合相", polarity: bodyValence };
  if (distance === 2 || distance === 10) return { label: "六合", polarity: () => 0.35 };
  if (distance === 3 || distance === 9) return { label: "刑相", polarity: () => -0.45 };
  if (distance === 4 || distance === 8) return { label: "拱相", polarity: () => 0.50 };
  if (distance === 6) return { label: "對分", polarity: () => -0.55 };
  return null;
}

const BODY_VALENCE: Record<AstroBody, number> = {
  SUN: 0.25, MOON: 0.10, MERCURY: 0, VENUS: 0.65, MARS: -0.45,
  JUPITER: 0.85, SATURN: -0.55, URANUS: 0, NEPTUNE: 0, PLUTO: 0,
};
function bodyValence(body: AstroBody): number { return BODY_VALENCE[body]; }

const BODY_AFFINITY: Record<AstroBody, NumberMap> = {
  SUN: affinities(.25, .20, .75, .25, .85),
  MOON: affinities(.20, .70, .20, .80, .75),
  MERCURY: affinities(.55, .35, 1.00, .70, .20),
  VENUS: affinities(.55, 1.00, .25, .95, .30),
  MARS: affinities(.35, .65, .85, .55, .85),
  JUPITER: affinities(.95, .45, .80, .60, .50),
  SATURN: affinities(.75, .35, .90, .45, .70),
  URANUS: affinities(.45, .45, .55, .55, .35),
  NEPTUNE: affinities(.35, .65, .45, .55, .50),
  PLUTO: affinities(.50, .55, .55, .55, .45),
};
function affinities(w: number, l: number, work: number, r: number, h: number): NumberMap {
  return { WEALTH: w, LOVE: l, WORK_STUDY: work, RELATIONSHIPS: r, HEALTH: h };
}
function affinity(body: AstroBody, domain: FortuneDomain): number { return BODY_AFFINITY[body][domain]; }

const HOUSE_WEIGHTS: Record<number, NumberMap> = {
  1: affinities(0, 0, .25, .20, 1.00),
  2: affinities(1.00, 0, 0, 0, 0),
  3: affinities(0, 0, .90, .50, 0),
  4: affinities(0, .45, 0, .55, .35),
  5: affinities(0, 1.00, .35, .25, 0),
  6: affinities(0, 0, .85, 0, 1.00),
  7: affinities(.25, 1.00, 0, 1.00, 0),
  8: affinities(.75, .45, 0, 0, .35),
  9: affinities(0, 0, .85, .25, 0),
  10: affinities(.40, 0, 1.00, .20, 0),
  11: affinities(.35, .25, .35, 1.00, 0),
  12: affinities(0, .25, .20, .25, .60),
};
function houseWeight(house: number, domain: FortuneDomain): number { return HOUSE_WEIGHTS[house]?.[domain] ?? 0; }

function retrogradeSensitivity(body: AstroBody): number {
  return ({ SUN: 0, MOON: 0, MERCURY: 1.00, VENUS: .80, MARS: .80, JUPITER: .45, SATURN: .45, URANUS: .12, NEPTUNE: .12, PLUTO: .12 } as const)[body];
}

const DOMICILE: Partial<Record<AstroBody, ZodiacSign[]>> = {
  SUN: ["LEO"], MOON: ["CANCER"], MERCURY: ["GEMINI", "VIRGO"], VENUS: ["TAURUS", "LIBRA"],
  MARS: ["ARIES", "SCORPIO"], JUPITER: ["SAGITTARIUS", "PISCES"], SATURN: ["CAPRICORN", "AQUARIUS"],
};
const DETRIMENT: Partial<Record<AstroBody, ZodiacSign[]>> = {
  SUN: ["AQUARIUS"], MOON: ["CAPRICORN"], MERCURY: ["SAGITTARIUS", "PISCES"], VENUS: ["ARIES", "SCORPIO"],
  MARS: ["TAURUS", "LIBRA"], JUPITER: ["GEMINI", "VIRGO"], SATURN: ["CANCER", "LEO"],
};
const EXALTATION: Partial<Record<AstroBody, ZodiacSign>> = {
  SUN: "ARIES", MOON: "TAURUS", MERCURY: "VIRGO", VENUS: "PISCES", MARS: "CAPRICORN", JUPITER: "CANCER", SATURN: "LIBRA",
};
const FALL: Partial<Record<AstroBody, ZodiacSign>> = {
  SUN: "LIBRA", MOON: "SCORPIO", MERCURY: "PISCES", VENUS: "VIRGO", MARS: "CANCER", JUPITER: "CAPRICORN", SATURN: "ARIES",
};
function dignityMultiplier(body: AstroBody, sign: ZodiacSign): number {
  if (!DOMICILE[body]) return 1.0;
  if (DOMICILE[body]!.includes(sign)) return 1.20;
  if (EXALTATION[body] === sign) return 1.15;
  if (DETRIMENT[body]!.includes(sign)) return .85;
  if (FALL[body] === sign) return .80;
  return 1.0;
}

export function zodiac(value: number): ZodiacSign {
  return ZODIACS[Math.min(11, Math.floor(normalize(value) / 30))];
}
export function signedAngularDelta(from: number, to: number): number {
  let delta = normalize(to) - normalize(from);
  if (delta > 180) delta -= 360;
  if (delta <= -180) delta += 360;
  return delta;
}
function aspectOrb(first: number, second: number, target: number): number {
  return Math.abs(Math.abs(signedAngularDelta(first, second)) - target);
}
function normalize(value: number): number { return mod(value, 360); }
function mod(value: number, divisor: number): number { return ((value % divisor) + divisor) % divisor; }
function clamp(value: number, min: number, max: number): number { return Math.min(max, Math.max(min, value)); }

export function noonSunLongitude(date: string): number { return longitude("SUN", taipeiInstant(date, 12, 0)); }
export function angularDistance(first: number, second: number): number { return Math.abs(signedAngularDelta(first, second)); }

export function closestSeasonalDate(originalDate: string, targetYear: number): string {
  const [yearText, monthText, dayText] = originalDate.split("-");
  void yearText;
  const month = Number(monthText);
  const day = Math.min(Number(dayText), daysInMonth(targetYear, month));
  const approximate = `${targetYear}-${pad2(month)}-${pad2(day)}`;
  const target = noonSunLongitude(originalDate);
  let bestDate = approximate;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (let offset = -6; offset <= 6; offset++) {
    const candidate = addDays(approximate, offset);
    const distance = angularDistance(target, noonSunLongitude(candidate));
    if (distance < bestDistance) {
      bestDate = candidate;
      bestDistance = distance;
    }
  }
  return bestDate;
}

export function randomParallelSourceDate(originalDate: string): string {
  const originalYear = Number(originalDate.slice(0, 4));
  let year: number;
  do year = secureRandomInt(1900, 2100); while (year === originalYear);
  return closestSeasonalDate(originalDate, year);
}

export function resolveParallel(originalDate: string, zodiacSign: ZodiacSign, sourceDate: string): ParallelSkyResult {
  const originalSunLongitude = noonSunLongitude(originalDate);
  const alteredSunLongitude = noonSunLongitude(sourceDate);
  const astronomy = analyzeDay(sourceDate);
  const destiny = calculate(zodiacSign, astronomy);
  return {
    originalDate,
    sourceDate,
    originalSunLongitude,
    alteredSunLongitude,
    sunLongitudeDifference: angularDistance(originalSunLongitude, alteredSunLongitude),
    astronomy,
    destiny,
  };
}

export function serializeAstronomy(data: AstronomyDayData) {
  return {
    date: data.date,
    samples: data.samples.map((sample) => ({ instant: sample.instant.toISOString(), longitudes: sample.longitudes })),
    bodies: Object.fromEntries(Object.entries(data.bodies).map(([key, value]) => [key, {
      ...value,
      ingresses: value.ingresses.map((ingress) => ({ ...ingress, nearTime: ingress.nearTime.toISOString() })),
    }])),
    aspects: data.aspects.map((hit) => ({ ...hit, closestTime: hit.closestTime.toISOString() })),
  };
}

export function persistenceRow(destiny: AstrologyDestiny) {
  return {
    zodiac_sign: destiny.zodiac,
    overall_grade: destiny.overallGrade,
    overall_score: destiny.overallScore,
    domain_scores: destiny.domainScores,
    domain_grades: destiny.domainGrades,
    explanations: { overall: destiny.overallExplanation, domains: destiny.domainExplanations },
    astrology_factors: destiny.factors,
  };
}

function secureRandomInt(min: number, max: number): number {
  const range = max - min + 1;
  const maxUint = 0x1_0000_0000;
  const limit = Math.floor(maxUint / range) * range;
  const values = new Uint32Array(1);
  let value: number;
  do {
    crypto.getRandomValues(values);
    value = values[0];
  } while (value >= limit);
  return min + (value % range);
}

export function taipeiToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: TAIPEI_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

function taipeiInstant(date: string, hour: number, minute: number): Date {
  return new Date(`${date}T${pad2(hour)}:${pad2(minute)}:00+08:00`);
}
function taipeiHm(date: Date): string {
  return new Intl.DateTimeFormat("zh-TW", { timeZone: TAIPEI_ZONE, hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}
function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
function daysInMonth(year: number, month: number): number { return new Date(Date.UTC(year, month, 0)).getUTCDate(); }
function pad2(value: number): string { return String(value).padStart(2, "0"); }