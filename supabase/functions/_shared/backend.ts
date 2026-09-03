import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.57.0";

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

export function adminClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const secretKey = readNamedKey("SUPABASE_SECRET_KEYS", "SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !secretKey) throw new Error("Supabase server environment is incomplete");
  return createClient(url, secretKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

function readNamedKey(jsonEnvironment: string, legacyEnvironment: string): string | null {
  const raw = Deno.env.get(jsonEnvironment);
  if (raw) {
    const parsed = JSON.parse(raw) as Record<string, string>;
    const first = parsed.default ?? Object.values(parsed)[0];
    if (first) return first;
  }
  return Deno.env.get(legacyEnvironment) ?? null;
}
