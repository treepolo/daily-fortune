// Final production weights derived from Astrology Weight Search v4 expanded run #3, scheme 1.
// Full validation of the selected search solution covered 880,968 destinies.
// The engine consumes these as direct factor affinities. There is no post-hoc factor multiplier layer.

export type WeightBody =
  "SUN" | "MOON" | "MERCURY" | "VENUS" | "MARS" |
  "JUPITER" | "SATURN" | "URANUS" | "NEPTUNE" | "PLUTO";
export type WeightDomain = "WEALTH" | "LOVE" | "WORK_STUDY" | "RELATIONSHIPS" | "HEALTH";
export type WeightAspect = "CONJUNCTION" | "SEXTILE" | "SQUARE" | "TRINE" | "OPPOSITION";
export type GradeThresholds = readonly [number, number, number, number, number, number];

type DomainMap = Record<WeightDomain, number>;

const BASE_BODY_AFFINITY: Record<WeightBody, DomainMap> = {
  SUN: values(.25, .20, .75, .25, .85),
  MOON: values(.20, .70, .20, .80, .75),
  MERCURY: values(.55, .35, 1.00, .70, .20),
  VENUS: values(.55, 1.00, .25, .95, .30),
  MARS: values(.35, .65, .85, .55, .85),
  JUPITER: values(.95, .45, .80, .60, .50),
  SATURN: values(.75, .35, .90, .45, .70),
  URANUS: values(.45, .45, .55, .55, .35),
  NEPTUNE: values(.35, .65, .45, .55, .50),
  PLUTO: values(.50, .55, .55, .55, .45),
};

const BODY_FACTOR_WEIGHT: Record<WeightBody, DomainMap> = {
  SUN: values(0.51, 0.51, 0.51, 2.4947068374668913, 1.6999539412162237),
  MOON: values(0.51, 0.51, 0.51, 0.980696998920567, 4.27618753164621),
  MERCURY: values(0.51, 0.51, 0.51, 0.6434907232000194, 0.7621881330894),
  VENUS: values(0.51, 0.51, 0.53, 0.51, 5.0),
  MARS: values(0.51, 0.51, 0.51, 2.4111657642116313, 5.0),
  JUPITER: values(0.51, 0.542285390000788, 0.51, 0.51, 0.9094967488489121),
  SATURN: values(0.51, 0.51, 0.5217122621987923, 1.8828778700425755, 2.5720658451861893),
  URANUS: values(0.51, 0.51, 0.51, 0.5589023667372353, 1.3085964778700374),
  NEPTUNE: values(0.51, 0.52, 0.51, 0.8835605935988553, 0.919518917587199),
  PLUTO: values(0.51, 0.51, 0.51, 0.708592917608228, 1.9454253226166283),
};

const HOUSE_FACTOR_SHIFT: Record<number, DomainMap> = {
  1: values(0.09738817218040904, 0.10438434369573962, -0.49, 0.1722310960128431, 3.3687856094398554),
  2: values(0.04732439350017288, 1.3640675836916227, -0.09227219589284655, 0.41577274992555596, 2.355450436187522),
  3: values(0.10584178664417757, 2.5006588344541023, 1.2974668537154557, -0.4445982256298708, 0.4853796099639558),
  4: values(0.4600825882783463, 3.952474950197802, -0.060924647374250455, 0.5661368271590519, 3.96464872212791),
  5: values(1.7538536860573992, 3.879845615959657, -0.49, 3.5088180180098405, 0.781689313699363),
  6: values(-0.4080344075928916, -0.16540695881831574, 0.7355963247899405, 1.8065809641339035, 3.716640669953562),
  7: values(0.4748953530154907, 2.8018348345398625, 2.090823961580444, -0.25188091695514647, 0.26668457655626054),
  8: values(-0.2320663707696265, 3.6550439511033272, 0.7399948303726827, 0.0006833510243929516, -0.22865679453223103),
  9: values(0.7495384010593853, 1.2844922461020216, 1.710164169490443, 0.005982092612865135, 1.0194271345679577),
  10: values(4.0, 1.4879096050108702, 4.0, 0.6537877336617459, 3.179098809485791),
  11: values(1.03508551602049, 1.9138180077716729, -0.49, -0.49, -0.13413650751385672),
  12: values(1.0972775591704513, 2.4504658124121548, 3.9863339895471572, 0.10204728992088452, 2.4945146384535035),
};

const ASPECT_FACTOR_SHIFT: Record<WeightAspect, DomainMap> = {
  CONJUNCTION: values(-0.49, -0.4402088883988888, -0.49, -0.08285351420200053, 0.13615800188717392),
  SEXTILE: values(-0.3404620466121937, -0.49, -0.49, 0.8277297856507762, 2.8874844235873485),
  SQUARE: values(-0.49, -0.49, -0.49, 3.7463143247741866, 0.731807795658827),
  TRINE: values(-0.49, -0.49, -0.49, -0.4839654818352665, 0.0755769767468027),
  OPPOSITION: values(-0.49, -0.4796692233263943, -0.4827433513983344, -0.4624583177309005, 4.0),
};

const HOUSE_RELEVANCE: Record<number, DomainMap> = {
  1: values(0, 0, .25, .20, 1.00),
  2: values(1.00, 0, 0, 0, 0),
  3: values(0, 0, .90, .50, 0),
  4: values(0, .45, 0, .55, .35),
  5: values(0, 1.00, .35, .25, 0),
  6: values(0, 0, .85, 0, 1.00),
  7: values(.25, 1.00, 0, 1.00, 0),
  8: values(.75, .45, 0, 0, .35),
  9: values(0, 0, .85, .25, 0),
  10: values(.40, 0, 1.00, .20, 0),
  11: values(.35, .25, .35, 1.00, 0),
  12: values(0, .25, .20, .25, .60),
};

export const DOMAIN_GRADE_THRESHOLDS: Record<WeightDomain, GradeThresholds> = {
  WEALTH: [-2.6958693347743794, -0.7891658490567275, 0.559749351357153, 1.8369966331193541, 3.5263472734595585, 6.705888849141977],
  LOVE: [-4.9034592643870845, -0.9712506003799356, 2.4604378068588537, 6.174031239650987, 11.924375679937432, 19.193948885625634],
  WORK_STUDY: [-11.343915741285572, -4.776617178762415, -1.0634062197761625, 2.103397480245748, 6.370229755115553, 13.055471797100873],
  RELATIONSHIPS: [-41.26169409571241, -27.202622633216063, -17.545410859875393, -9.691399532809182, -1.8669574181941215, 4.985062171435285],
  HEALTH: [-51.88102994960997, -31.240914435589918, -14.904592746207063, -1.2734250247423522, 12.98518127381329, 28.511420760760743],
};

export const OVERALL_GRADE_THRESHOLDS: GradeThresholds = [
  -15.958460591679342,
  -10.33723444757988,
  -5.228616858268023,
  -0.8107079713325007,
  3.6728116233269645,
  7.940461609302975,
];

export function bodyFactorAffinity(body: WeightBody, domain: WeightDomain): number {
  return BASE_BODY_AFFINITY[body][domain] * BODY_FACTOR_WEIGHT[body][domain];
}

export function houseFactorAffinity(body: WeightBody, house: number, domain: WeightDomain): number {
  return BASE_BODY_AFFINITY[body][domain] * HOUSE_RELEVANCE[house][domain] *
    (BODY_FACTOR_WEIGHT[body][domain] + HOUSE_FACTOR_SHIFT[house][domain]);
}

export function aspectFactorAffinity(
  first: WeightBody,
  second: WeightBody,
  aspect: WeightAspect,
  domain: WeightDomain,
): number {
  const baseAffinity = (BASE_BODY_AFFINITY[first][domain] + BASE_BODY_AFFINITY[second][domain]) / 2;
  const calibratedWeight = (BODY_FACTOR_WEIGHT[first][domain] + BODY_FACTOR_WEIGHT[second][domain]) / 2 +
    ASPECT_FACTOR_SHIFT[aspect][domain];
  return baseAffinity * calibratedWeight;
}

export function houseRelevance(house: number, domain: WeightDomain): number {
  return HOUSE_RELEVANCE[house]?.[domain] ?? 0;
}

function values(wealth: number, love: number, workStudy: number, relationships: number, health: number): DomainMap {
  return { WEALTH: wealth, LOVE: love, WORK_STUDY: workStudy, RELATIONSHIPS: relationships, HEALTH: health };
}
