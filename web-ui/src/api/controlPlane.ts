export type ConnectionState = "CONNECTING" | "LIVE" | "DEGRADED" | "OFFLINE";

export interface RuntimeSnapshot {
  status: string;
  apiVersion: string;
  version: string;
  activeProjectCount: number;
  headSequence: number;
  activeAgentCount: number;
  connectedPeerCount: number;
  networkStatus: string;
  relayStatus: string;
}

export interface ProjectSnapshot {
  id: string;
  name: string;
  path: string;
  createdAt: string;
}

export interface KnownProjectSnapshot extends ProjectSnapshot {
  firstObservedAt: string;
  lastObservedAt: string;
  status: "LIVE" | "INACTIVE" | "UNAVAILABLE" | "IDENTITY_MISMATCH";
}

export interface ProviderSnapshot {
  id: string;
  supportLevel: string;
  status: string;
}

export interface SelectorSnapshot {
  kind?: string;
  type?: string;
  value?: string;

  [key: string]: unknown;
}

export interface AgentSnapshot {
  id: string;
  provider: string;
  goal: string;
  state: string;
  lastVerifiedActivity: string | null;
  claims: SelectorSnapshot[];
  currentWork: WorkIntentSnapshot | null;
  waitingOn: string[];
}

export interface WorkIntentSnapshot {
  intentId: string;
  taskId: string;
  goal: string;
  acceptance: string;
  status: string;
  role: string;
  workGroupId: string;
}

export interface WorkGroupSnapshot {
  id: string;
  status: string;
  version: number;
  goal: string;
  acceptance: string;
  participants: string[];
}

export interface ClaimSnapshot {
  intentId: string;
  participant: string;
  workGroupId: string;
  role: string;
  state: string;
  selectors: SelectorSnapshot[];
  conflicts: Array<{ participant: string; intentId: string; selector: SelectorSnapshot }>;
}

export interface CapabilitySnapshot {
  handle: string;
  capability: string;
  requester: string;
  owner: string;
  state: string;
  reason: string;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
  contract: {
    inputs: string[];
    output: string;
    requiredBehavior: string;
    acceptanceTests: string[];
  };
}

export interface TaskSnapshot {
  id: string;
  title: string;
  capability: string;
  owner: string;
}

export interface OwnershipSnapshot {
  capability: string;
  taskId: string;
  owner: string;
  protectedScopes: string[];
  intentVersion: number;
}

export interface NetworkPeerSnapshot {
  nodeId: string;
  sessionId: string;
  liveness: string;
  usable: boolean;
  establishedAt: string;
  authenticated: boolean;
  health: string;
}

export interface NetworkRouteSnapshot {
  kind: string;
  destinationNodeId: string;
  path: string[];
  nextHop: string;
  relayNodeId: string;
}

export interface OverlayMemberSnapshot {
  nodeId: string;
  status: string;
}

export interface OverlayEdgeSnapshot {
  from: string;
  to: string;
  status: string;
}

export interface OverlaySnapshot {
  status: string;
  authorityNodeId: string;
  revision: number;
  memberCount: number;
  expiresAt: string;
  members: OverlayMemberSnapshot[];
  directEdges: OverlayEdgeSnapshot[];
  desiredEdges: OverlayEdgeSnapshot[];
}

export interface RelaySnapshot {
  status: string;
  localAddress: string;
  activeConnections: number;
  configured: boolean;
  connected: boolean;
  relayIdentity: string;
  authorized: boolean;
  activeRouteUsage: number;
}

export interface NetworkSnapshot {
  status: string;
  peers: NetworkPeerSnapshot[];
  routes: NetworkRouteSnapshot[];
  overlay: OverlaySnapshot;
  relay: RelaySnapshot;
}

export interface DiagnosticFinding {
  code: string;
  severity: string;
  confidence: string;
  summary: string;
  explanation: string;
  affectedResourceType: string;
  repairSupported: boolean;
  recommendation: string;
}

export interface DiagnosticsSnapshot {
  schemaVersion: string;
  reportId: string;
  projectId: string;
  timestampEpochMillis: number;
  overallStatus: string;
  criticalCount: number;
  errorCount: number;
  warningCount: number;
  infoCount: number;
  cleanupRecommended: boolean;
  reconciliationRecommended: boolean;
  repairAvailable: boolean;
  findings: DiagnosticFinding[];
}

export interface Snapshot {
  apiVersion: string;
  runtime: RuntimeSnapshot;
  project: ProjectSnapshot | null;
  knownProjects: KnownProjectSnapshot[];
  providers: ProviderSnapshot[];
  agents: AgentSnapshot[];
  workgroups: WorkGroupSnapshot[];
  claims: ClaimSnapshot[];
  capabilities: CapabilitySnapshot[];
  tasks: TaskSnapshot[];
  ownerships: OwnershipSnapshot[];
  network: NetworkSnapshot;
  onboarding?: OnboardingSnapshot;
  diagnostics: DiagnosticsSnapshot;
}

export interface InviteResponse {
  apiVersion: string;
  operationId: string;
  inviteUri: string;
  peerIdentity: string;
  expiresAt: string;
  state: string;
}

export interface JoinResponse {
  apiVersion: string;
  operationId: string;
  answerUri: string;
  peerIdentity: string;
  expiresAt: string;
  state: string;
}

export interface OperationResponse {
  apiVersion: string;
  operationId: string;
  state: string;
}

export interface ProjectSelectionProject {
  projectId: string;
  displayName: string;
}

export interface ProjectSelection {
  apiVersion?: string;
  state: "PROJECT_SELECTION_REQUIRED";
  selectionId: string;
  expiresAt: string;
  projects: ProjectSelectionProject[];
}

export interface PendingJoinSnapshot {
  operationId: string;
  state: string;
  expiresAt: string;
}

export interface OnboardingSnapshot {
  pendingJoins: PendingJoinSnapshot[];
}

export interface ServerEvent {
  type: string;
  id: string;
  data: Record<string, unknown>;
}

export interface ProjectFolderChoice {
  state: "SELECTED" | "CANCELLED";
  path: string;
  name: string;
}

export class ControlPlaneError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ControlPlaneError";
    this.status = status;
    this.code = code;
  }
}

const API_PREFIX = "/api/v1";

export function consumeBootstrapToken(): string | null {
  const raw = window.location.hash.replace(/^#/, "");
  const params = new URLSearchParams(raw);
  const token = params.get("bootstrap");
  if (token) {
    const selectionId = params.get("selectionId");
    const suffix = selectionId ? `#selectionId=${encodeURIComponent(selectionId)}` : "";
    window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}${suffix}`);
  }
  return token;
}

export function selectionIdFromLocation(): string | null {
  const raw = window.location.hash.replace(/^#/, "");
  return new URLSearchParams(raw).get("selectionId");
}

export function clearSelectionId(): void {
  window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
}

export function normalizeSnapshot(input: Snapshot): Snapshot {
  const network = input.network ?? ({} as NetworkSnapshot);
  const overlay = network.overlay ?? ({} as OverlaySnapshot);
  const relay = network.relay ?? ({} as RelaySnapshot);
  return {
    ...input,
    knownProjects: input.knownProjects ?? [],
    providers: input.providers ?? [],
    agents: input.agents ?? [],
    workgroups: input.workgroups ?? [],
    claims: input.claims ?? [],
    capabilities: input.capabilities ?? [],
    tasks: input.tasks ?? [],
    ownerships: input.ownerships ?? [],
    onboarding: {
      pendingJoins: input.onboarding?.pendingJoins ?? [],
    },
    network: {
      status: network.status ?? "UNCONFIGURED",
      peers: network.peers ?? [],
      routes: network.routes ?? [],
      overlay: {
        status: overlay.status ?? "UNCONFIGURED",
        authorityNodeId: overlay.authorityNodeId ?? "",
        revision: overlay.revision ?? 0,
        memberCount: overlay.memberCount ?? 0,
        expiresAt: overlay.expiresAt ?? "",
        members: overlay.members ?? [],
        directEdges: overlay.directEdges ?? [],
        desiredEdges: overlay.desiredEdges ?? [],
      },
      relay: {
        status: relay.status ?? "DISABLED",
        localAddress: relay.localAddress ?? "",
        activeConnections: relay.activeConnections ?? 0,
        configured: relay.configured ?? false,
        connected: relay.connected ?? false,
        relayIdentity: relay.relayIdentity ?? "",
        authorized: relay.authorized ?? false,
        activeRouteUsage: relay.activeRouteUsage ?? 0,
      },
    },
    diagnostics: {
      ...(input.diagnostics ?? ({} as DiagnosticsSnapshot)),
      findings: input.diagnostics?.findings ?? [],
    },
  };
}

export function parseSseBlock(block: string): ServerEvent | null {
  let type = "message";
  let id = "";
  const data: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("event:")) type = line.slice(6).trim();
    if (line.startsWith("id:")) id = line.slice(3).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (data.length === 0) return null;
  return {type, id, data: JSON.parse(data.join("\n")) as Record<string, unknown>};
}

async function readError(response: Response): Promise<ControlPlaneError> {
  let code = "REQUEST_FAILED";
  let message = `Control plane request failed (${response.status})`;
  try {
    const body = (await response.json()) as { error?: { code?: string; message?: string } };
    code = body.error?.code ?? code;
    message = body.error?.message ?? message;
  } catch {
    // Preserve the stable status-based error when the server did not return JSON.
  }
  return new ControlPlaneError(response.status, code, message);
}

export class ControlPlaneClient {
  private sessionToken = "";
  private csrfToken = "";

  async startSession(bootstrapToken: string): Promise<Snapshot> {
    const response = await fetch(`${API_PREFIX}/session`, {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({bootstrapToken}),
    });
    if (!response.ok) throw await readError(response);
    const credentials = (await response.json()) as { sessionToken: string; csrfToken: string };
    this.sessionToken = credentials.sessionToken;
    this.csrfToken = credentials.csrfToken;
    return this.snapshot();
  }

  async snapshot(): Promise<Snapshot> {
    const response = await this.request(`${API_PREFIX}/snapshot`);
    return normalizeSnapshot((await response.json()) as Snapshot);
  }

  async invite(expectedPeer?: string): Promise<InviteResponse> {
    return this.command<InviteResponse>("invite", expectedPeer ? {expectedPeer} : {});
  }

  async join(inviteUri: string): Promise<JoinResponse> {
    return this.command<JoinResponse>("join", {inviteUri});
  }

  async answer(operationId: string, answerUri: string): Promise<OperationResponse> {
    return this.command<OperationResponse>("answer", {operationId, answerUri});
  }

  async connect(operationId: string): Promise<OperationResponse> {
    return this.command<OperationResponse>("connect", {operationId});
  }

  async projectSelection(selectionId: string): Promise<ProjectSelection> {
    const response = await this.request(`${API_PREFIX}/selection/${encodeURIComponent(selectionId)}`);
    return (await response.json()) as ProjectSelection;
  }

  async selectProject(selectionId: string, projectId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("select-project", {selectionId, projectId});
  }

  async registerProject(path: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("register-project", {path});
  }

  async chooseProject(): Promise<ProjectFolderChoice> {
    return this.command<ProjectFolderChoice>("choose-project", {});
  }

  async removeProject(projectId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("remove-project", {projectId});
  }

  startEvents(handlers: {
    onSnapshot(snapshot: Snapshot): void;
    onEvent(event: ServerEvent): void;
    onError(error: Error): void;
  }): () => void {
    let stopped = false;
    let controller: AbortController | undefined;

    const run = async (): Promise<void> => {
      while (!stopped) {
        controller = new AbortController();
        try {
          const response = await fetch(`${API_PREFIX}/events`, {
            headers: {Accept: "text/event-stream", "X-Synesis-Control-Session": this.sessionToken},
            signal: controller.signal,
          });
          if (!response.ok || !response.body) throw await readError(response);
          await consumeSse(response.body, (event) => {
            if (event.type === "snapshot") {
              const snapshot = event.data.snapshot as Snapshot;
              handlers.onSnapshot(normalizeSnapshot(snapshot));
            } else {
              handlers.onEvent(event);
            }
          });
          if (!stopped) await delay(400);
        } catch (error) {
          if (stopped) return;
          if (error instanceof ControlPlaneError && (error.status === 401 || error.status === 403)) {
            handlers.onError(error);
            return;
          }
          handlers.onError(error instanceof Error ? error : new Error("SSE connection failed"));
          await delay(1_000);
        }
      }
    };
    void run();
    return () => {
      stopped = true;
      controller?.abort();
    };
  }

  private async command<T>(name: string, body: Record<string, string>): Promise<T> {
    const response = await this.request(`${API_PREFIX}/commands/${name}`, {
      method: "POST",
      headers: {"Content-Type": "application/json", "X-Synesis-Control-CSRF": this.csrfToken},
      body: JSON.stringify(body),
    });
    return (await response.json()) as T;
  }

  private async request(url: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("X-Synesis-Control-Session", this.sessionToken);
    const response = await fetch(url, {...init, headers});
    if (!response.ok) throw await readError(response);
    return response;
  }
}

async function consumeSse(stream: ReadableStream<Uint8Array>, onEvent: (event: ServerEvent) => void): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const {done, value} = await reader.read();
    buffer += decoder.decode(value, {stream: !done});
    let separator = buffer.indexOf("\n\n");
    while (separator >= 0) {
      const block = buffer.slice(0, separator);
      buffer = buffer.slice(separator + 2);
      const event = parseSseBlock(block);
      if (event) onEvent(event);
      separator = buffer.indexOf("\n\n");
    }
    if (done) return;
  }
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
