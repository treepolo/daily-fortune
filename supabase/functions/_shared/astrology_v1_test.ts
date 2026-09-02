import {
  DOMAINS,
  analyzeDay,
  calculate,
  noonSunLongitude,
} from "./astrology_v1.ts";
import {
  PARALLEL_MAX_DATE,
  PARALLEL_MIN_DATE,
  PARALLEL_SOURCE_DATE_COUNT,
  sourceDateForIndex,
} from "./parallel_random.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertNear(actual: number, expected: number, epsilon: number, message: string) {
  if (Math.abs(actual - expected) > epsilon) {
    throw new Error(`${message}: expected ${expected}, got ${actual}`);
  }
}

Deno.test("astronomy samples cover complete Taipei day", () => {
  const data = analyzeDay("2026-09-02");
  assert(data.samples.length === 97, `Expected 97 samples, got ${data.samples.length}`);
  assert(Object.keys(data.samples[0].longitudes).length === 10, "Expected 10 bodies");
  assert(Object.keys(data.bodies).length === 10, "Expected 10 body summaries");
  assert(data.aspects.every((hit) => hit.orbDegrees <= hit.aspect.maxOrb), "Aspect outside configured orb");
});

Deno.test("March equinox Sun is near tropical Aries zero", () => {
  const value = noonSunLongitude("2026-03-20");
  const distance = Math.min(value, 360 - value);
  assert(distance < 2, `Sun longitude was ${value}`);
});

Deno.test("parallel source index covers every day from 1900 through 2100", () => {
  assert(PARALLEL_MIN_DATE === "1900-01-01", `Unexpected minimum ${PARALLEL_MIN_DATE}`);
  assert(PARALLEL_MAX_DATE === "2100-12-31", `Unexpected maximum ${PARALLEL_MAX_DATE}`);
  assert(PARALLEL_SOURCE_DATE_COUNT === 73_414, `Unexpected day count ${PARALLEL_SOURCE_DATE_COUNT}`);
  assert(sourceDateForIndex(0) === "1900-01-01", "First source date mismatch");
  assert(sourceDateForIndex(31) === "1900-02-01", "Source index must move across months directly");
  assert(sourceDateForIndex(PARALLEL_SOURCE_DATE_COUNT - 1) === "2100-12-31", "Last source date mismatch");
});

Deno.test("audit factors reconstruct every domain score", () => {
  const astronomy = analyzeDay("2026-09-02");
  const destiny = calculate("SCORPIO", astronomy);
  for (const domain of DOMAINS) {
    const reconstructed = destiny.factors.reduce((sum, factor) => sum + factor.contributions[domain], 0);
    assertNear(reconstructed, destiny.domainScores[domain], 1e-9, `Score mismatch for ${domain}`);
  }
  const average = DOMAINS.reduce((sum, domain) => sum + destiny.domainScores[domain], 0) / DOMAINS.length;
  assertNear(average, destiny.overallScore, 1e-9, "Overall score mismatch");
});
