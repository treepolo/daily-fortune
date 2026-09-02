import { DOMAINS, ZODIACS, calculateDay, type FortuneGrade } from "./astrology_v1.ts";

type Fixture = {
  date: string;
  signs: Record<string, {
    overallScore: number;
    overallGrade: FortuneGrade;
    domains: Record<string, { score: number; grade: FortuneGrade }>;
  }>;
};

const path = Deno.args[0] ?? "app/build/astrology-kotlin-fixture.json";
const fixture = JSON.parse(await Deno.readTextFile(path)) as Fixture;
const calculated = calculateDay(fixture.date).destinies;
const epsilon = 1e-8;

for (const zodiac of ZODIACS) {
  const expected = fixture.signs[zodiac];
  if (!expected) throw new Error(`Missing Kotlin fixture for ${zodiac}`);
  const actual = calculated[zodiac];
  assertNear(actual.overallScore, expected.overallScore, `${zodiac} overall score`);
  if (actual.overallGrade !== expected.overallGrade) {
    throw new Error(`${zodiac} overall grade: Kotlin=${expected.overallGrade}, Deno=${actual.overallGrade}`);
  }
  for (const domain of DOMAINS) {
    const expectedDomain = expected.domains[domain];
    if (!expectedDomain) throw new Error(`Missing Kotlin fixture for ${zodiac}/${domain}`);
    assertNear(actual.domainScores[domain], expectedDomain.score, `${zodiac}/${domain} score`);
    if (actual.domainGrades[domain] !== expectedDomain.grade) {
      throw new Error(`${zodiac}/${domain} grade: Kotlin=${expectedDomain.grade}, Deno=${actual.domainGrades[domain]}`);
    }
  }
}

console.log(`Astrology parity PASS: ${fixture.date}, ${ZODIACS.length} signs, ${DOMAINS.length} domains.`);

function assertNear(actual: number, expected: number, label: string) {
  const difference = Math.abs(actual - expected);
  if (difference > epsilon) {
    throw new Error(`${label}: Kotlin=${expected}, Deno=${actual}, |delta|=${difference}`);
  }
}
