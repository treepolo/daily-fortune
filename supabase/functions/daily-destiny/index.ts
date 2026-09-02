import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.57.0";
import {
  ASTROLOGY_ENGINE_VERSION,
  EPHEMERIS_VERSION,
  ZODIACS,
  calculateDay,
  persistenceRow,
  randomParallelSourceDate,
  resolveParallel,
  serializeAstronomy,
  taipeiToday,
  type ZodiacSign,
} from "../_shared/astrology_v1.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-cron-secret",
};

export default {
  async fetch(req: Request): Promise<Response> {
    if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
    if (req.method !== "POST") return json({ error: "POST required" }, 405);

    try {
      const body = await req.json().catch(() => ({}));
      const action = body.action ?? "ensure-public";
      const admin = adminClient();

      if (action === "ensure-public") {
        await requireUserOrCron(req);
        const date = taipeiToday();
        const rows = await ensurePublic(admin, date);
        return json({ date, engine_version: ASTROLOGY_ENGINE_VERSION, rows });
      }

      if (action === "reroll") {
        const user = await requireUser(req);
        const date = taipeiToday();
        await applyPendingZodiac(admin, user.id, date);
        const { data: profile, error: profileError } = await admin
          .from("profiles")
          .select("zodiac_sign")
          .eq("id", user.id)
          .single();
        if (profileError) throw profileError;
        const zodiac = profile?.zodiac_sign as ZodiacSign | null;
        if (!zodiac || !ZODIACS.includes(zodiac)) return json({ error: "Zodiac is not configured" }, 409);

        await ensurePublic(admin, date);
        const sourceDate = randomParallelSourceDate(date);
        const parallel = resolveParallel(date, zodiac, sourceDate);
        const localId = resolveLocalId(body.local_id);
        const destiny = parallel.destiny;
        const payload = {
          fortune_date: date,
          zodiac_sign: zodiac,
          engine_version: ASTROLOGY_ENGINE_VERSION,
          ephemeris_version: EPHEMERIS_VERSION,
          parallel_source_date: sourceDate,
          original_sun_longitude: parallel.originalSunLongitude,
          altered_sun_longitude: parallel.alteredSunLongitude,
          sun_longitude_difference: parallel.sunLongitudeDifference,
          overall_grade: destiny.overallGrade,
          overall_score: destiny.overallScore,
          domain_scores: destiny.domainScores,
          domain_grades: destiny.domainGrades,
          explanations: { overall: destiny.overallExplanation, domains: destiny.domainExplanations },
          astronomy_snapshot: serializeAstronomy(parallel.astronomy),
          astrology_factors: destiny.factors,
        };
        const { data: reroll, error: rerollError } = await admin.rpc("commit_personal_reroll", {
          p_user_id: user.id,
          p_local_id: localId,
          p_payload: payload,
        });
        if (rerollError) throw rerollError;
        return json({ reroll });
      }

      return json({ error: "Unknown action" }, 400);
    } catch (error) {
      console.error(error);
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};

async function ensurePublic(admin: SupabaseClient, date: string) {
  const { data: existing, error: existingError } = await admin
    .from("daily_zodiac_destinies")
    .select("*")
    .eq("fortune_date", date);
  if (existingError) throw existingError;
  if (existing?.length === 12) return existing;
  if (existing && existing.length !== 0) throw new Error(`Partial public destiny state exists for ${date}`);

  const calculated = calculateDay(date);
  const rows = ZODIACS.map((zodiac) => persistenceRow(calculated.destinies[zodiac]));
  const { data, error } = await admin.rpc("commit_daily_zodiac_destinies", {
    p_fortune_date: date,
    p_engine_version: ASTROLOGY_ENGINE_VERSION,
    p_ephemeris_version: EPHEMERIS_VERSION,
    p_astronomy_snapshot: serializeAstronomy(calculated.astronomy),
    p_rows: rows,
  });
  if (error) throw error;
  return data;
}

async function applyPendingZodiac(admin: SupabaseClient, userId: string, today: string) {
  const { data: profile, error } = await admin
    .from("profiles")
    .select("pending_zodiac_sign,pending_zodiac_effective_date")
    .eq("id", userId)
    .single();
  if (error) throw error;
  if (profile?.pending_zodiac_sign && profile.pending_zodiac_effective_date && profile.pending_zodiac_effective_date <= today) {
    const { error: updateError } = await admin.from("profiles").update({
      zodiac_sign: profile.pending_zodiac_sign,
      pending_zodiac_sign: null,
      pending_zodiac_effective_date: null,
      updated_at: new Date().toISOString(),
    }).eq("id", userId);
    if (updateError) throw updateError;
  }
}

function adminClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const secretKey = readNamedKey("SUPABASE_SECRET_KEYS", "SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !secretKey) throw new Error("Supabase server environment is incomplete");
  return createClient(url, secretKey, { auth: { persistSession: false, autoRefreshToken: false } });
}

async function requireUser(req: Request) {
  const authorization = req.headers.get("Authorization");
  if (!authorization) throw new Error("Authentication required");
  const url = Deno.env.get("SUPABASE_URL");
  const publishableKey = readNamedKey("SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY");
  if (!url || !publishableKey) throw new Error("Supabase client environment is incomplete");
  const client = createClient(url, publishableKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await client.auth.getUser();
  if (error || !data.user) throw new Error("Invalid user session");
  return data.user;
}

async function requireUserOrCron(req: Request) {
  const configured = Deno.env.get("DAILY_DESTINY_CRON_SECRET");
  const supplied = req.headers.get("x-cron-secret");
  if (configured && supplied && timingSafeEqual(configured, supplied)) return;
  await requireUser(req);
}

function readNamedKey(jsonEnvironment: string, legacyEnvironment: string): string | null {
  const raw = Deno.env.get(jsonEnvironment);
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as Record<string, string>;
      const first = parsed.default ?? Object.values(parsed)[0];
      if (first) return first;
    } catch {
      throw new Error(`${jsonEnvironment} is not valid JSON`);
    }
  }
  return Deno.env.get(legacyEnvironment) ?? null;
}

function resolveLocalId(value: unknown): string {
  if (value == null) return crypto.randomUUID();
  if (typeof value !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) {
    throw new Error("local_id must be a UUID");
  }
  return value;
}

function timingSafeEqual(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left[i] ^ right[i];
  return difference === 0;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}
