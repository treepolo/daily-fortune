import { adminClient, corsHeaders, json } from "../_shared/backend.ts";
import {
  resolvedConfigId,
  resolveExperiments,
  type ExperimentRow,
  type JsonObject,
  type VariantRow,
} from "../_shared/experiment_core.ts";

export default {
  async fetch(req: Request): Promise<Response> {
    if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
    if (req.method !== "GET") return json({ error: "GET required" }, 405);

    try {
      const url = new URL(req.url);
      const installationId = url.searchParams.get("installation_id") ?? "";
      if (!isUuid(installationId)) return json({ error: "installation_id must be a UUID" }, 400);

      const admin = adminClient();
      const { data: baseRow, error: baseError } = await admin
        .from("app_config")
        .select("config,updated_at")
        .eq("id", "default")
        .single();
      if (baseError || !baseRow) throw baseError ?? new Error("Default app_config is missing");

      const { data: experimentRows, error: experimentError } = await admin
        .from("experiments")
        .select("id,rollout,salt,priority,starts_at,ends_at")
        .eq("status", "ACTIVE")
        .order("priority", { ascending: true });
      if (experimentError) throw experimentError;

      const experiments = (experimentRows ?? []) as ExperimentRow[];
      let variants: VariantRow[] = [];
      if (experiments.length > 0) {
        const { data: variantRows, error: variantError } = await admin
          .from("experiment_variants")
          .select("experiment_id,variant_id,weight,treatment")
          .in("experiment_id", experiments.map((experiment) => experiment.id));
        if (variantError) throw variantError;
        variants = (variantRows ?? []) as VariantRow[];
      }

      const resolved = await resolveExperiments(
        baseRow.config as JsonObject,
        installationId,
        experiments,
        variants,
      );
      const configId = await resolvedConfigId(
        String(baseRow.updated_at),
        resolved.assignments,
        resolved.config,
      );

      return json({
        ...resolved.config,
        config_id: configId,
        assignments: resolved.assignments,
      });
    } catch (error) {
      console.error(error);
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
