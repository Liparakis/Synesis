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

export interface ConversationMemberSnapshot {
  participant?: string;
  claims?: string[];
  lane?: string;
}

export interface ConversationMessageSnapshot {
  from: ConversationMemberSnapshot;
  sentAtEpochMillis: number;
  text: string;
}

export interface ConversationSnapshot {
  conversation: string;
  members: ConversationMemberSnapshot[];
  earlierMessages: number;
  messages: ConversationMessageSnapshot[];
}

export interface OperatorRequestSnapshot {
  id: string;
  condition: string;
  raisedBy: string;
  summary: string;
  evidence: string;
}

export interface TaskSnapshot {
  id: string;
  title: string;
  capability: string;
  owner: string;
  /** The WorkGroup its lanes work in, when exactly one is known. */
  workGroupId?: string | null;
}

/** A snapshot whose reviewers disagreed: open while its state is review_disputed, otherwise the state says how it ended. */
export interface DisputeSnapshot {
  snapshotId: string;
  taskId?: string;
  workGroupId?: string | null;
  author?: string;
  verdicts: Record<string, string>;
  state: string;
}

/** Work kept in the trash: taken over, finished after its goal was met, or given up in a dispute (ADR-0098 step 7). */
export interface TrashSnapshot {
  laneId: string;
  epoch: number;
  taskId: string;
  workGroupId: string;
  commit: string;
  reason: string;
  trashedBy: string;
  restoredInto: string[];
  emptied: boolean;
}

export interface OwnershipSnapshot {
  capability: string;
  taskId: string;
  workGroupId?: string | null;
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
  /** The part of the setup it is about: project, environment, network or work_groups. */
  group?: string;
  /** How sure the doctor is: confirmed, suspected or advice. */
  mark?: string;
  /** One command or step a person can take. */
  fix?: string;
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

/** One computer of a shared project. state is SELF, CONNECTED, DISCONNECTED (away) or RUNTIME_STOPPED. */
export interface MemberSnapshot {
  nodeId: string;
  self: boolean;
  owner: boolean;
  state: string;
  since: string | null;
  lastReceived: string | null;
}

/** Who belongs to the project; `members` is empty for a project on one computer. */
/** A set of agents the project's runtime started for the page, with what it last printed. */
export interface AgentRunSnapshot {
  id: string;
  state: string;
  agents: string[];
  recent: string[];
}

/** One agent to start: an installed provider, and optionally the model and effort to run it with. */
export interface AgentChoice {
  provider: string;
  model?: string;
  effort?: string;
}

export interface MembershipSnapshot {
  shared: boolean;
  owner: string | null;
  self: string | null;
  selfIsOwner: boolean;
  members: MemberSnapshot[];
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
  conversations: ConversationSnapshot[];
  operatorRequests: OperatorRequestSnapshot[];
  tasks: TaskSnapshot[];
  disputes: DisputeSnapshot[];
  trash: TrashSnapshot[];
  ownerships: OwnershipSnapshot[];
  membership: MembershipSnapshot;
  agentRuns: AgentRunSnapshot[];
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
  /** "PROJECT" for a project invitation; absent for a plain connection invitation. */
  kind?: string;
  /** The inviter's code remote URL (no credentials); null when the project has none. */
  codeRemoteUrl?: string | null;
  /** The Git remote that URL belongs to; null when there is no URL. */
  codeRemoteName?: string | null;
  /** Why there is no URL: NO_GIT_REMOTE, SEVERAL_GIT_REMOTES, CODE_REMOTE_MISSING or URL_NOT_SHAREABLE. */
  codeRemoteReason?: string | null;
}

/** What a project invitation says, read before anything is done with it. */
export interface ProjectLinkDescription {
  apiVersion: string;
  hostNodeId: string;
  projectId: string;
  /** The folder name of the project when this computer knows it. */
  projectName?: string | null;
  codeRemoteUrl?: string | null;
  expiresAt: string;
}

/** What opening a project invitation answers: connecting, or a request for the folder that holds the project. */
export interface ProjectLinkResponse {
  apiVersion: string;
  state: "CONNECTING" | "PROJECT_NOT_FOUND";
  projectId?: string;
  hostNodeId?: string;
  codeRemoteUrl?: string | null;
  operationId?: string;
  answerUri?: string;
  peerIdentity?: string;
  expiresAt?: string;
  folder?: string;
  codeRemote?: string | null;
  /** Whether this computer can fetch from codeRemote without a prompt: REACHABLE, or the refusal code. */
  codeRemoteCheck?: string;
  /** Git's reason and what to run when the check failed. */
  codeRemoteDetail?: string;
}

export interface JoinRequestSnapshot {
  nodeId: string;
  requestedAt: string;
  expiresAt: string;
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
  /** Computers that opened this project's invitation and wait for the owner's decision. */
  joinRequests: JoinRequestSnapshot[];
}

export interface OperatorRecheckResult {
  state: string;
  reason: string | null;
  failed: boolean;
  openRequests: number;
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
    // The bootstrap token goes; what the link that opened this tab carried stays until the page has used it.
    const kept = new URLSearchParams();
    for (const key of ["selectionId", "projectLink"]) {
      const value = params.get(key);
      if (value) kept.set(key, value);
    }
    const suffix = kept.size > 0 ? `#${kept.toString()}` : "";
    window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}${suffix}`);
  }
  return token;
}

export function selectionIdFromLocation(): string | null {
  const raw = window.location.hash.replace(/^#/, "");
  return new URLSearchParams(raw).get("selectionId");
}

/** The project invitation a clicked synesis:// link handed to this tab, if any. */
export function projectLinkFromLocation(): string | null {
  const raw = window.location.hash.replace(/^#/, "");
  return new URLSearchParams(raw).get("projectLink");
}

export function clearSelectionId(): void {
  window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
}

/** A snapshot as a runtime may send it: one built before membership was added has none. */
type SnapshotInput = Omit<Snapshot, "membership" | "agentRuns"> & {membership?: Partial<MembershipSnapshot>; agentRuns?: AgentRunSnapshot[]};

export function normalizeSnapshot(input: SnapshotInput): Snapshot {
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
    conversations: input.conversations ?? [],
    operatorRequests: input.operatorRequests ?? [],
    agentRuns: input.agentRuns ?? [],
    tasks: input.tasks ?? [],
    disputes: input.disputes ?? [],
    trash: input.trash ?? [],
    ownerships: input.ownerships ?? [],
    membership: {
      shared: input.membership?.shared ?? false,
      owner: input.membership?.owner ?? null,
      self: input.membership?.self ?? null,
      selfIsOwner: input.membership?.selfIsOwner ?? false,
      members: input.membership?.members ?? [],
    },
    onboarding: {
      pendingJoins: input.onboarding?.pendingJoins ?? [],
      joinRequests: input.onboarding?.joinRequests ?? [],
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

// The session survives a reload of its own tab and nothing else: session storage is per tab and
// is gone when the tab closes. The bootstrap token in the address is already spent by then.
const SESSION_KEY = "synesis.control.session";

function storedSession(): {sessionToken: string; csrfToken: string} | null {
  try {
    const stored = JSON.parse(window.sessionStorage.getItem(SESSION_KEY) ?? "null") as {sessionToken?: unknown; csrfToken?: unknown} | null;
    return stored && typeof stored.sessionToken === "string" && typeof stored.csrfToken === "string"
      ? {sessionToken: stored.sessionToken, csrfToken: stored.csrfToken} : null;
  } catch {
    return null;
  }
}

function storeSession(session: {sessionToken: string; csrfToken: string} | null): void {
  try {
    if (session) window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
    else window.sessionStorage.removeItem(SESSION_KEY);
  } catch {
    // Without session storage a reload asks for the synesis ui command again, as before.
  }
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
    storeSession({sessionToken: this.sessionToken, csrfToken: this.csrfToken});
    return this.snapshot();
  }

  /**
   * Picks up the session this tab already had, after a reload. Returns null when there is none or
   * the runtime no longer accepts it.
   */
  async resumeSession(): Promise<Snapshot | null> {
    const stored = storedSession();
    if (!stored) return null;
    this.sessionToken = stored.sessionToken;
    this.csrfToken = stored.csrfToken;
    try {
      return await this.snapshot();
    } catch (error) {
      if (error instanceof ControlPlaneError && error.status === 401) storeSession(null);
      this.sessionToken = "";
      this.csrfToken = "";
      return null;
    }
  }

  async snapshot(): Promise<Snapshot> {
    const response = await this.request(`${API_PREFIX}/snapshot`);
    return normalizeSnapshot((await response.json()) as SnapshotInput);
  }

  async invite(expectedPeer?: string, openRouterPort = false, project = false): Promise<InviteResponse> {
    return this.command<InviteResponse>("invite", {...(expectedPeer ? {expectedPeer} : {}), ...(openRouterPort ? {openRouterPort} : {}), ...(project ? {project} : {})});
  }

  /** Opens a project invitation: finds the project's folder, follows the inviter, and connects. */
  async openProjectLink(link: string, folder?: string): Promise<ProjectLinkResponse> {
    return this.command<ProjectLinkResponse>("open-project-link", {link, ...(folder ? {folder} : {})});
  }

  /** Reads a project invitation without joining. */
  async describeProjectLink(link: string): Promise<ProjectLinkDescription> {
    return this.command<ProjectLinkDescription>("describe-project-link", {link});
  }

  async acceptJoin(nodeId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("accept-join", {nodeId});
  }

  async declineJoin(nodeId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("decline-join", {nodeId});
  }

  async join(inviteUri: string): Promise<JoinResponse> {
    return this.command<JoinResponse>("join", {inviteUri});
  }

  async answer(operationId: string, answerUri: string): Promise<OperationResponse> {
    return this.command<OperationResponse>("answer", {operationId, answerUri});
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

  /** Removes any number of projects from the registry in one durable step. */
  async removeProjects(projectIds: string[]): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("remove-project", {projectIds});
  }

  /** Brings trashed work back in a work group of its own. */
  async restoreTrash(laneId: string, epoch: number): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("restore-trash", {laneId, epoch});
  }

  /** Deletes trashed work for good, on every computer; the person has confirmed it. */
  async emptyTrash(laneId: string, epoch: number): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("empty-trash", {laneId, epoch, confirmed: true});
  }

  /** Asks Synesis to look again at what its operator requests wait for, and to integrate what was waiting. */
  async recheckOperatorRequests(): Promise<OperatorRecheckResult> {
    return this.command<OperatorRecheckResult>("recheck-operator-requests", {});
  }

  /** Closes a request an agent put to the operator; Synesis closes the ones it raised itself. */
  async closeOperatorRequest(requestId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("close-operator-request", {requestId});
  }

  /** Starts agents on providers installed in the project: with a task, or with none (task null) so they wait for one. */
  async startAgents(agents: AgentChoice[], task: string | null): Promise<{runId: string; state: string}> {
    return this.command<{runId: string; state: string}>("start-agents", task === null ? {agents, waitForTask: true} : {agents, prompt: task});
  }

  /** Stops a set of agents this page started. */
  async stopAgents(runId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("stop-agents", {runId});
  }

  /** Gives the agents that wait for a task their task, or replaces the task of those working. It is only text for them: it grants nothing. */
  async sendAgentTask(text: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("send-agent-task", {text});
  }

  /** Removes a computer from the project (owner only); the person has confirmed it. */
  async removeMember(nodeId: string): Promise<Record<string, unknown>> {
    return this.command<Record<string, unknown>>("remove-member", {nodeId, confirmed: true});
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

  private async command<T>(name: string, body: Record<string, unknown>): Promise<T> {
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
