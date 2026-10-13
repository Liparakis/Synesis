import {afterEach, vi} from "vitest";
import {ControlPlaneClient, normalizeSnapshot, parseSseBlock} from "./controlPlane";

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
      ownerships: [],
      network: {status: "UNCONFIGURED"} as never,
      diagnostics: {} as never,
    });
    expect(snapshot.network.status).toBe("UNCONFIGURED");
    expect(snapshot.network.overlay.status).toBe("UNCONFIGURED");
    expect(snapshot.network.relay.status).toBe("DISABLED");
    expect(snapshot.network.peers).toEqual([]);
  });
});
