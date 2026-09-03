export type JsonObject = Record<string, unknown>;

export type ExperimentRow = {
  id: string;
  rollout: number;
  salt: string;
  priority: number;
  starts_at: string | null;
  ends_at: string | null;
};

export type VariantRow = {
  experiment_id: string;
  variant_id: string;
  weight: number;
  treatment: JsonObject;
};

export type Assignment = {
  experiment_id: string;
  variant_id: string;
};

export async function resolveExperiments(
  baseConfig: JsonObject,
  installationId: string,
  experiments: ExperimentRow[],
  variants: VariantRow[],
  now = new Date(),
): Promise<{ config: JsonObject; assignments: Assignment[] }> {
  let config = structuredClone(baseConfig);
  const assignments: Assignment[] = [];
  const active = experiments
    .filter((experiment) => isActiveAt(experiment, now))
    .sort((a, b) => a.priority - b.priority || a.id.localeCompare(b.id));

  for (const experiment of active) {
    if (experiment.rollout <= 0) continue;
    const bucket = await stableUnitInterval(`${experiment.salt}:${experiment.id}:${installationId}`);
    if (bucket >= experiment.rollout) continue;
    const experimentVariants = variants
      .filter((variant) => variant.experiment_id === experiment.id)
      .sort((a, b) => a.variant_id.localeCompare(b.variant_id));
    const variant = chooseVariant(experimentVariants, bucket / experiment.rollout);
    if (!variant) continue;
    config = deepMerge(config, variant.treatment);
    assignments.push({ experiment_id: experiment.id, variant_id: variant.variant_id });
  }

  return { config, assignments };
}

export function chooseVariant(variants: VariantRow[], unit: number): VariantRow | null {
  const valid = variants.filter((variant) => Number.isFinite(variant.weight) && variant.weight > 0);
  const total = valid.reduce((sum, variant) => sum + variant.weight, 0);
  if (total <= 0) return null;
  const target = Math.min(Math.max(unit, 0), 1 - Number.EPSILON) * total;
  let cumulative = 0;
  for (const variant of valid) {
    cumulative += variant.weight;
    if (target < cumulative) return variant;
  }
  return valid.at(-1) ?? null;
}

export function deepMerge(base: JsonObject, overlay: JsonObject): JsonObject {
  const result: JsonObject = structuredClone(base);
  for (const [key, value] of Object.entries(overlay)) {
    const current = result[key];
    if (isPlainObject(current) && isPlainObject(value)) {
      result[key] = deepMerge(current, value);
    } else {
      result[key] = structuredClone(value);
    }
  }
  return result;
}

export async function resolvedConfigId(
  baseUpdatedAt: string,
  assignments: Assignment[],
  config: JsonObject,
): Promise<string> {
  const payload = JSON.stringify({ baseUpdatedAt, assignments, config });
  const bytes = new TextEncoder().encode(payload);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return `resolved-${toHex(digest).slice(0, 20)}`;
}

export async function stableUnitInterval(key: string): Promise<number> {
  const bytes = new TextEncoder().encode(key);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  let integer = 0n;
  for (let index = 0; index < 7; index++) integer = (integer << 8n) | BigInt(digest[index]);
  integer >>= 3n; // 53 deterministic bits, exactly representable by Number.
  return Number(integer) / 2 ** 53;
}

function isActiveAt(experiment: ExperimentRow, now: Date): boolean {
  if (experiment.starts_at && new Date(experiment.starts_at) > now) return false;
  if (experiment.ends_at && new Date(experiment.ends_at) <= now) return false;
  return true;
}

function isPlainObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
