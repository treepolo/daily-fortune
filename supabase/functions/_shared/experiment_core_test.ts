import {
  chooseVariant,
  deepMerge,
  resolveExperiments,
  stableUnitInterval,
  type VariantRow,
} from "./experiment_core.ts";

Deno.test("stable assignment hash is deterministic", async () => {
  const a = await stableUnitInterval("salt:experiment:installation");
  const b = await stableUnitInterval("salt:experiment:installation");
  assert(a === b, "same key must produce same bucket");
  assert(a >= 0 && a < 1, "bucket must be in [0, 1)");
});

Deno.test("multi-arm weights are normalized", () => {
  const variants: VariantRow[] = [
    { experiment_id: "e", variant_id: "A", weight: 1, treatment: {} },
    { experiment_id: "e", variant_id: "B", weight: 2, treatment: {} },
    { experiment_id: "e", variant_id: "C", weight: 1, treatment: {} },
  ];
  assert(chooseVariant(variants, 0.10)?.variant_id === "A", "first quarter should be A");
  assert(chooseVariant(variants, 0.30)?.variant_id === "B", "middle half should be B");
  assert(chooseVariant(variants, 0.90)?.variant_id === "C", "last quarter should be C");
});

Deno.test("deep merge replaces arrays and preserves unrelated config", () => {
  const merged = deepMerge(
    { fortune: { initial_distribution: { probabilities: [1, 2, 3] }, overall_rule: { type: "FLOOR" } } },
    { fortune: { initial_distribution: { probabilities: [3, 2, 1] } } },
  );
  const fortune = merged.fortune as Record<string, unknown>;
  const distribution = fortune.initial_distribution as Record<string, unknown>;
  const rule = fortune.overall_rule as Record<string, unknown>;
  assert(JSON.stringify(distribution.probabilities) === JSON.stringify([3, 2, 1]), "array should be replaced");
  assert(rule.type === "FLOOR", "unrelated nested config must survive");
});

Deno.test("same installation resolves to same variant", async () => {
  const experiments = [{
    id: "probability-v1",
    rollout: 1,
    salt: "stable-salt",
    priority: 0,
    starts_at: null,
    ends_at: null,
  }];
  const variants: VariantRow[] = [
    { experiment_id: "probability-v1", variant_id: "A", weight: 1, treatment: { marker: "A" } },
    { experiment_id: "probability-v1", variant_id: "B", weight: 1, treatment: { marker: "B" } },
    { experiment_id: "probability-v1", variant_id: "C", weight: 1, treatment: { marker: "C" } },
  ];
  const installation = "11111111-1111-4111-8111-111111111111";
  const first = await resolveExperiments({}, installation, experiments, variants);
  const second = await resolveExperiments({}, installation, experiments, variants);
  assert(first.assignments[0]?.variant_id === second.assignments[0]?.variant_id, "assignment must be stable");
});

Deno.test("ramping rollout never changes variant for already enrolled installations", async () => {
  const variants: VariantRow[] = [
    { experiment_id: "probability-v2", variant_id: "A", weight: 1, treatment: { marker: "A" } },
    { experiment_id: "probability-v2", variant_id: "B", weight: 1, treatment: { marker: "B" } },
    { experiment_id: "probability-v2", variant_id: "C", weight: 1, treatment: { marker: "C" } },
    { experiment_id: "probability-v2", variant_id: "D", weight: 1, treatment: { marker: "D" } },
  ];
  const baseExperiment = {
    id: "probability-v2",
    rollout: 0.2,
    salt: "ramp-salt",
    priority: 0,
    starts_at: null,
    ends_at: null,
  };

  for (let index = 0; index < 500; index++) {
    const suffix = index.toString(16).padStart(12, "0");
    const installation = `11111111-1111-4111-8111-${suffix}`;
    const atTwenty = await resolveExperiments({}, installation, [baseExperiment], variants);
    if (atTwenty.assignments.length === 0) continue;
    const atHundred = await resolveExperiments(
      {},
      installation,
      [{ ...baseExperiment, rollout: 1 }],
      variants,
    );
    assert(
      atTwenty.assignments[0].variant_id === atHundred.assignments[0]?.variant_id,
      "rollout ramp must preserve the existing variant",
    );
  }
});

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}
