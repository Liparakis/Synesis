import {normalizeSnapshot, parseSseBlock} from "./controlPlane";

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
      providers: [],
      agents: [],
      workgroups: [],
      claims: [],
      capabilities: [],
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
