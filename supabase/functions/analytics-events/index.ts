import { adminClient, corsHeaders, json } from "../_shared/backend.ts";

type IncomingEvent = {
  event_id?: unknown;
  installation_id?: unknown;
  session_id?: unknown;
  event_name?: unknown;
  event_epoch_millis?: unknown;
  local_datetime?: unknown;
  timezone_id?: unknown;
  app_version?: unknown;
  config_id?: unknown;
  assignments?: unknown;
  payload?: unknown;
};

export default {
  async fetch(req: Request): Promise<Response> {
    if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
    if (req.method !== "POST") return json({ error: "POST required" }, 405);

    try {
      const contentLength = Number(req.headers.get("content-length") ?? "0");
      if (Number.isFinite(contentLength) && contentLength > 512_000) {
        return json({ error: "payload too large" }, 413);
      }
      const body = await req.json() as { events?: unknown };
      if (!Array.isArray(body.events) || body.events.length === 0 || body.events.length > 50) {
        return json({ error: "events must contain 1..50 items" }, 400);
      }

      const rows = body.events.map((event, index) => normalizeEvent(event as IncomingEvent, index));
      const admin = adminClient();
      const { error } = await admin
        .from("analytics_events")
        .upsert(rows, { onConflict: "event_id", ignoreDuplicates: true });
      if (error) throw error;
      return json({ accepted: rows.length });
    } catch (error) {
      console.error(error);
      const message = error instanceof Error ? error.message : String(error);
      const status = message.startsWith("event[") ? 400 : 500;
      return json({ error: message }, status);
    }
  },
};

function normalizeEvent(event: IncomingEvent, index: number) {
  const prefix = `event[${index}]`;
  const eventId = requiredString(event.event_id, `${prefix}.event_id`);
  const installationId = requiredString(event.installation_id, `${prefix}.installation_id`);
  if (!isUuid(eventId)) throw new Error(`${prefix}.event_id must be a UUID`);
  if (!isUuid(installationId)) throw new Error(`${prefix}.installation_id must be a UUID`);

  const epochMillis = Number(event.event_epoch_millis);
  if (!Number.isFinite(epochMillis) || epochMillis <= 0) {
    throw new Error(`${prefix}.event_epoch_millis must be positive`);
  }
  const eventAt = new Date(epochMillis);
  if (!Number.isFinite(eventAt.getTime())) throw new Error(`${prefix}.event_epoch_millis is invalid`);

  const assignments = event.assignments ?? [];
  const payload = event.payload ?? {};
  if (!Array.isArray(assignments)) throw new Error(`${prefix}.assignments must be an array`);
  if (!isObject(payload)) throw new Error(`${prefix}.payload must be an object`);

  return {
    event_id: eventId,
    installation_id: installationId,
    session_id: requiredString(event.session_id, `${prefix}.session_id`, 128),
    event_name: requiredString(event.event_name, `${prefix}.event_name`, 64),
    event_epoch_millis: Math.trunc(epochMillis),
    event_at: eventAt.toISOString(),
    local_datetime: requiredString(event.local_datetime, `${prefix}.local_datetime`, 64),
    timezone_id: requiredString(event.timezone_id, `${prefix}.timezone_id`, 128),
    app_version: requiredString(event.app_version, `${prefix}.app_version`, 64),
    config_id: requiredString(event.config_id, `${prefix}.config_id`, 128),
    assignments,
    payload,
  };
}

function requiredString(value: unknown, field: string, maxLength = 256): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw new Error(`${field} must be a non-empty string <= ${maxLength} chars`);
  }
  return value;
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
