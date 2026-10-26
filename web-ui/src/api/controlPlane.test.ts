import {afterEach, vi} from "vitest";
import {ControlPlaneClient, normalizeSnapshot, parseSseBlock, type Snapshot} from "./controlPlane";

function respond(status: number, body: unknown): Response {
  return {ok: status >= 200 && status < 300, status, json: async () => body} as Response;
}

describe("control-plane session across a reload", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.sessionStorage.clear();
  });

  it("carries on with the tab's session, and gives it up once the runtime refuses it", async () => {
    const sessions: Array<string | null> = [];
    let accepted = true;
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/session")) return respond(201, {sessionToken: "session-1", csrfToken: "csrf-1"});
      sessions.push(new Headers(init?.headers).get("X-Synesis-Control-Session"));
      return accepted ? respond(200, {apiVersion: "v1"}) : respond(401, {error: {code: "AUTH_REQUIRED"}});
    }));

    expect(await new ControlPlaneClient().resumeSession()).toBeNull();
    await new ControlPlaneClient().startSession("bootstrap");

    // A reload builds a new client; the bootstrap token is spent, the session is not.
    const reloaded = await new ControlPlaneClient().resumeSession();
    expect(reloaded?.apiVersion).toBe("v1");
    expect(sessions).toEqual(["session-1", "session-1"]);

    accepted = false;
    expect(await new ControlPlaneClient().resumeSession()).toBeNull();
    expect(window.sessionStorage.length).toBe(0);
  });
});

describe("control-plane client helpers", () => {
  it("parses the semantic SSE contract", () => {
    expect(parseSseBlock('event: peer.connected\nid: 4\ndata: {"type":"peer.connected"}')).toEqual({
      type: "peer.connected",
      id: "4",
      data: {type: "peer.connected"},
    });
  });

  it("keeps unconfigured network state explicit while normalizing collections", () => {
    const snapshot = normalizeSnapshot({
      apiVersion: "v1",
      runtime: {} as never,
      project: null,
      knownProjects: [],
      providers: [],
      agents: [],
      workgroups: [],
      claims: [],
      capabilities: [],
      conversations: [],
      operatorRequests: [],
      tasks: [],
      disputes: [],
      trash: [],
      ownerships: [],
      network: {status: "UNCONFIGURED"} as never,
      diagnostics: {} as never,
    });
    expect(snapshot.network.status).toBe("UNCONFIGURED");
    expect(snapshot.network.overlay.status).toBe("UNCONFIGURED");
    expect(snapshot.network.relay.status).toBe("DISABLED");
    expect(snapshot.network.peers).toEqual([]);
  });

  it("reads a snapshot from an older control plane that sends no disputes as having none", () => {
    const snapshot = normalizeSnapshot({
      apiVersion: "v1",
      runtime: {} as never,
      project: null,
      knownProjects: [],
      providers: [],
      agents: [],
      workgroups: [],
      claims: [],
      capabilities: [],
      conversations: [],
      operatorRequests: [],
      tasks: [],
      ownerships: [],
      network: {status: "UNCONFIGURED"} as never,
      diagnostics: {} as never,
    } as unknown as Snapshot);
    expect(snapshot.disputes).toEqual([]);
    expect(snapshot.trash).toEqual([]);
  });

  it("keeps the disputes a control plane sends", () => {
    const disputes = [{snapshotId: "snap-1", taskId: "task-1", author: "agt-a", verdicts: {"agt-b": "ACCEPTED", "agt-c": "REJECTED"}, state: "review_disputed"}];
    const snapshot = normalizeSnapshot({
      apiVersion: "v1",
      runtime: {} as never,
      project: null,
      knownProjects: [],
      providers: [],
      agents: [],
      workgroups: [],
      claims: [],
      capabilities: [],
      conversations: [],
      operatorRequests: [],
      tasks: [],
      disputes,
      trash: [],
      ownerships: [],
      network: {status: "UNCONFIGURED"} as never,
      diagnostics: {} as never,
    });
    expect(snapshot.disputes).toEqual(disputes);
  });
});

describe("membership and the operator and member commands", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.sessionStorage.clear();
  });

  it("gives a snapshot from a runtime without membership an empty one, and keeps what a runtime sends", () => {
    const base = {apiVersion: "v1", runtime: {} as never, project: null, network: {status: "UNCONFIGURED"} as never, diagnostics: {} as never};
    expect(normalizeSnapshot(base as never).membership).toEqual({shared: false, owner: null, self: null, selfIsOwner: false, members: []});

    const members = [{nodeId: "node-a", self: true, owner: true, state: "SELF", since: null, lastReceived: null}];
    expect(normalizeSnapshot({...base, membership: {shared: true, owner: "node-a", self: "node-a", selfIsOwner: true, members}} as never).membership)
      .toEqual({shared: true, owner: "node-a", self: "node-a", selfIsOwner: true, members});
  });

  it("posts the operator commands and a confirmed member removal to the command routes", async () => {
    const posted: Array<{url: string; body: unknown; csrf: string | null}> = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/session")) return respond(201, {sessionToken: "session-1", csrfToken: "csrf-1"});
      if (init?.method === "POST") posted.push({url, body: JSON.parse(String(init.body)), csrf: new Headers(init.headers).get("X-Synesis-Control-CSRF")});
      return respond(200, {apiVersion: "v1", state: "OK"});
    }));
    const client = new ControlPlaneClient();
    await client.startSession("bootstrap");

    await client.recheckOperatorRequests();
    await client.closeOperatorRequest("opr_1");
    await client.removeMember("node-b");

    expect(posted.filter((request) => !request.url.endsWith("/session")).map(({url, body, csrf}) => ({url, body, csrf}))).toEqual([
      {url: "/api/v1/commands/recheck-operator-requests", body: {}, csrf: "csrf-1"},
      {url: "/api/v1/commands/close-operator-request", body: {requestId: "opr_1"}, csrf: "csrf-1"},
      {url: "/api/v1/commands/remove-member", body: {nodeId: "node-b", confirmed: true}, csrf: "csrf-1"},
    ]);
  });

  it("posts starting, stopping and tasking agents to the command routes with only the fields the runtime accepts", async () => {
    const posted: Array<{url: string; body: unknown; csrf: string | null}> = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/session")) return respond(201, {sessionToken: "session-1", csrfToken: "csrf-1"});
      if (init?.method === "POST") posted.push({url, body: JSON.parse(String(init.body)), csrf: new Headers(init.headers).get("X-Synesis-Control-CSRF")});
      return respond(200, {apiVersion: "v1", state: "OK"});
    }));
    const client = new ControlPlaneClient();
    await client.startSession("bootstrap");

    await client.startAgents([{provider: "claude", model: "sonnet"}], "Implement X");
    await client.startAgents([{provider: "codex"}], null);
    await client.stopAgents("run-1");
    await client.sendAgentTask("Fix the parser");

    expect(posted.filter((request) => !request.url.endsWith("/session")).map(({url, body, csrf}) => ({url, body, csrf}))).toEqual([
      {url: "/api/v1/commands/start-agents", body: {agents: [{provider: "claude", model: "sonnet"}], prompt: "Implement X"}, csrf: "csrf-1"},
      {url: "/api/v1/commands/start-agents", body: {agents: [{provider: "codex"}], waitForTask: true}, csrf: "csrf-1"},
      {url: "/api/v1/commands/stop-agents", body: {runId: "run-1"}, csrf: "csrf-1"},
      {url: "/api/v1/commands/send-agent-task", body: {text: "Fix the parser"}, csrf: "csrf-1"},
    ]);
  });

  it("gives a snapshot from a runtime without agent runs an empty list", () => {
    const base = {apiVersion: "v1", runtime: {} as never, project: null, network: {status: "UNCONFIGURED"} as never, diagnostics: {} as never};
    expect(normalizeSnapshot(base as never).agentRuns).toEqual([]);
    const runs = [{id: "run-1", state: "RUNNING", agents: ["codex"], recent: []}];
    expect(normalizeSnapshot({...base, agentRuns: runs} as never).agentRuns).toEqual(runs);
  });
});
