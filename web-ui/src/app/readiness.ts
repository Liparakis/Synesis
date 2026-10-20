import type {DiagnosticFinding, Snapshot} from "../api/controlPlane";

/** ok: nothing to do; attention: something a person should look at; pending: not yet, and nothing is wrong. */
export type ReadinessStatus = "ok" | "attention" | "pending";

export interface ReadinessCheck {
  id: "agents-connected" | "agents-found" | "agents-working" | "runtime" | "computers" | "firewall" | "operator";
  label: string;
  status: ReadinessStatus;
  detail: string;
  /** One command or step that fixes or advances it, when there is one. */
  fix?: string;
}

/** Connected means the provider's managed files are installed in this project. */
const connectedProviders = (snapshot: Snapshot) => snapshot.providers.filter((provider) => provider.status === "INSTALLED").map((provider) => provider.id);

const findingsOf = (snapshot: Snapshot, ...codes: string[]): DiagnosticFinding[] =>
  snapshot.diagnostics.findings.filter((finding) => codes.includes(finding.code));

const plural = (count: number, one: string, many = one + "s") => count + " " + (count === 1 ? one : many);

/**
 * The checks a newcomer needs to know Synesis is ready, read from the snapshot alone: the providers and agents, the
 * runtime, the doctor's findings by code and the project's members. Nothing is decided here that the control plane did
 * not already say; the fix text is the doctor's own.
 */
export function readinessChecks(snapshot: Snapshot): ReadinessCheck[] {
  const connected = connectedProviders(snapshot);
  const missing = findingsOf(snapshot, "AGENT_NOT_ON_PATH", "PROVIDER_FILES_STALE");
  const checks: ReadinessCheck[] = [
    connected.length > 0
      ? {id: "agents-connected", label: "Agents connected", status: "ok", detail: connected.join(", ") + " connected to this project."}
      : {id: "agents-connected", label: "Agents connected", status: "attention", detail: "No agent is connected to this project.", fix: "synesis init"},
    connected.length === 0
      ? {id: "agents-found", label: "Agents found on this computer", status: "pending", detail: "Connect an agent first."}
      : missing.length === 0
        ? {id: "agents-found", label: "Agents found on this computer", status: "ok", detail: "Every connected agent is on this computer."}
        : {id: "agents-found", label: "Agents found on this computer", status: "attention", detail: missing[0].summary, fix: missing[0].fix},
    snapshot.agents.length > 0
      ? {id: "agents-working", label: "Agents at work", status: "ok", detail: plural(snapshot.agents.length, "agent") + " known to this project."}
      : {id: "agents-working", label: "Agents at work", status: "pending", detail: "No agent has started work yet."},
    snapshot.runtime.status === "RUNNING"
      ? {id: "runtime", label: "Runtime", status: "ok", detail: "The project's runtime is running" + (snapshot.runtime.version ? " (version " + snapshot.runtime.version + ")." : ".")}
      : {id: "runtime", label: "Runtime", status: "attention", detail: "The project's runtime is not running.", fix: "synesis ui"},
    computersCheck(snapshot),
  ];
  const firewall = findingsOf(snapshot, "LINK_FIREWALL_MISSING", "LINK_FIREWALL_STALE");
  if (snapshot.membership.shared) {
    checks.push(firewall.length === 0
      ? {id: "firewall", label: "Firewall", status: "ok", detail: "No firewall problem found for Link."}
      : {id: "firewall", label: "Firewall", status: "attention", detail: firewall[0].summary, fix: firewall[0].fix});
  }
  const waiting = snapshot.operatorRequests.length;
  checks.push(waiting === 0
    ? {id: "operator", label: "Waiting for you", status: "ok", detail: "Nothing is waiting for you."}
    : {id: "operator", label: "Waiting for you", status: "attention", detail: waiting === 1 ? "1 operator request waits for you." : waiting + " operator requests wait for you.", fix: "Open Coordination, then Operator"});
  return checks;
}

function computersCheck(snapshot: Snapshot): ReadinessCheck {
  const others = snapshot.membership.members.filter((member) => !member.self);
  if (!snapshot.membership.shared) {
    return {id: "computers", label: "Computers", status: "ok", detail: "This project runs on this computer only."};
  }
  const away = others.filter((member) => member.state !== "CONNECTED");
  return away.length === 0
    ? {id: "computers", label: "Computers", status: "ok", detail: plural(others.length, "other computer") + ", all connected."}
    : {id: "computers", label: "Computers", status: "attention", detail: away.length + " of " + plural(others.length, "other computer") + " away.", fix: "synesis doctor"};
}

/** How many checks want a person's attention; the always-available panel shows it. */
export const attentionCount = (checks: ReadinessCheck[]) => checks.filter((check) => check.status === "attention").length;

/** A project nobody has worked in yet: no agent is known and no WorkGroup exists. */
export const isFirstRun = (snapshot: Snapshot) => snapshot.agents.length === 0 && snapshot.workgroups.length === 0;

/** The one command to run next: connect an agent, or give a connected one its task. */
export function nextCommand(snapshot: Snapshot): string {
  const [agent] = connectedProviders(snapshot);
  return agent ? 'synesis agents run --agent ' + agent + ' --prompt "Implement X"' : "synesis init";
}
