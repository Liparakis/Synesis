export type StatusTone = "good" | "warn" | "bad" | "neutral";

export function cx(...parts: Array<string | false | null | undefined>) {
  return parts.filter(Boolean).join(" ");
}

export function formatStatus(value: string) {
  return (value || "UNKNOWN").replaceAll("_", " ");
}

export function formatDate(value: string | number | null | undefined) {
  if (value === null || value === undefined || value === "") return "—";
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return String(value);
  return date.toLocaleString([], {dateStyle: "medium", timeStyle: "short"});
}

export function statusTone(value: string): StatusTone {
  const normalized = (value || "").toUpperCase();
  if (["LIVE", "RUNNING", "READY", "INSTALLED", "ACTIVE", "CONNECTED", "CONFIGURED", "HEALTHY", "OK", "PROJECT_MEMBER", "ESTABLISHED", "SUPPORTED", "SUCCESS"].includes(normalized)) return "good";
  if (["WARNING", "WARN", "DEGRADED", "WAITING", "WAITING_DEP", "CLAIM_HELD", "BLOCKED", "PENDING", "UNCONFIGURED"].includes(normalized)) return "warn";
  if (["ERROR", "CRITICAL", "FAILED", "UNAVAILABLE", "UNREACHABLE", "IDENTITY_MISMATCH", "OFFLINE", "CONFLICT"].includes(normalized)) return "bad";
  return "neutral";
}

export function shortNode(nodeId: string): string {
  return nodeId.length > 16 ? nodeId.slice(0, 12) + "…" : nodeId;
}
