import {render, screen} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {AgentsView, DiagnosticsView, NetworkView, ProjectHeader, ProjectSelectionView, ProjectsView, RegistryProjectView} from "./App";
import {parseRoute, routePath} from "./routes";
import {ControlPlaneClient, type AgentSnapshot, type ProjectSelection, type Snapshot} from "../api/controlPlane";
import {vi} from "vitest";

const emptySnapshot = {
  project: null,
  runtime: {status: "RUNNING"},
  network: {peers: [], routes: []},
} as unknown as Snapshot;

const registrySnapshot = {
  ...emptySnapshot,
  apiVersion: "v1",
  project: {id: "proj_live", name: "Live project", path: "C:\\projects\\live", createdAt: "2026-09-09T00:00:00Z"},
  knownProjects: [
    {id: "proj_live", name: "Live project", path: "C:\\projects\\live", createdAt: "2026-09-09T00:00:00Z", firstObservedAt: "2026-09-09T00:00:00Z", lastObservedAt: "2026-09-09T00:00:00Z", status: "LIVE"},
    {id: "proj_idle", name: "Idle project", path: "C:\\projects\\idle", createdAt: "2026-09-08T00:00:00Z", firstObservedAt: "2026-09-08T00:00:00Z", lastObservedAt: "2026-09-08T00:00:00Z", status: "INACTIVE"},
  ],
} as unknown as Snapshot;

const diagnostics = {
  schemaVersion: "v1",
  reportId: "doc-test",
  projectId: "proj_test",
  timestampEpochMillis: Date.parse("2026-09-10T00:00:00Z"),
  overallStatus: "WARN",
  criticalCount: 0,
  errorCount: 0,
  warningCount: 1,
  infoCount: 1,
  cleanupRecommended: false,
  reconciliationRecommended: false,
  repairAvailable: false,
  findings: [
    {
      code: "WARN_ONE",
      severity: "WARNING",
      confidence: "0.91",
      summary: "First finding",
      explanation: "The first finding explanation.",
      affectedResourceType: "Agent",
      repairSupported: false,
      recommendation: "no_action",
    },
    {
      code: "INFO_TWO",
      severity: "INFO",
      confidence: "0.99",
      summary: "Second finding",
      explanation: "The second finding explanation.",
      affectedResourceType: "Network",
      repairSupported: false,
      recommendation: "CONFIRMED",
    },
  ],
} as Snapshot["diagnostics"];

describe("truthful product states", () => {
  it("requires an explicit eligible-project choice and posts only that choice", async () => {
    const user = userEvent.setup();
    const selectProject = vi.fn().mockResolvedValue({ok: true, state: "DISPATCHED"});
    const client = {selectProject} as unknown as ControlPlaneClient;
    const selection = {
      state: "PROJECT_SELECTION_REQUIRED",
      selectionId: "selection-1",
      expiresAt: "2026-09-12T00:02:00Z",
      projects: [
        {projectId: "project-a", displayName: "Alpha"},
        {projectId: "project-b", displayName: "Beta"},
      ],
    } as ProjectSelection;
    const onCompleted = vi.fn();

    render(<ProjectSelectionView selection={selection} client={client} onCompleted={onCompleted} onError={vi.fn()}/>);
    expect(screen.getByRole("heading", {name: "Choose project for invitation"})).toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(2);
    expect(selectProject).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", {name: /Beta/}));
    expect(selectProject).toHaveBeenCalledWith("selection-1", "project-b");
    expect(onCompleted).toHaveBeenCalledOnce();
  });

  it("disables every candidate while a selection is in flight", async () => {
    const user = userEvent.setup();
    const selectProject = vi.fn().mockImplementation(() => new Promise<Record<string, unknown>>(() => undefined));
    const selection = {
      state: "PROJECT_SELECTION_REQUIRED",
      selectionId: "selection-1",
      expiresAt: "2026-09-12T00:02:00Z",
      projects: [{projectId: "project-a", displayName: "Alpha"}, {projectId: "project-b", displayName: "Beta"}],
    } as ProjectSelection;

    render(<ProjectSelectionView selection={selection} client={{selectProject} as unknown as ControlPlaneClient} onCompleted={vi.fn()} onError={vi.fn()}/>);
    await user.click(screen.getByRole("button", {name: /Alpha/}));
    expect(screen.getAllByRole("button").every((button) => (button as HTMLButtonElement).disabled)).toBe(true);
  });

  it("shows an empty projects state instead of demo content", () => {
    render(<ProjectsView snapshot={emptySnapshot}/>);
    expect(screen.getByText("No projects yet")).toBeInTheDocument();
    expect(screen.queryByText("Project Alpha")).not.toBeInTheDocument();
  });

  it("supports an empty agents state", () => {
    render(<AgentsView agents={[]}/>);
    expect(screen.getByText("No agents yet")).toBeInTheDocument();
  });

  it("opens agent details in a popup instead of an inline inspector", async () => {
    const user = userEvent.setup();
    const agent = {
      id: "agent-planner-01",
      provider: "anthropic/claude-3-5-sonnet",
      goal: "Refactor AST parser",
      state: "CLAIM_HELD",
      lastVerifiedActivity: "2026-09-10T05:44:00Z",
      claims: [],
      currentWork: null,
      waitingOn: [],
    } as unknown as AgentSnapshot;

    render(<AgentsView agents={[agent]}/>);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await user.click(screen.getByRole("row", {name: /agent-planner-01/}));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("heading", {name: "agent-planner-01"})).toBeInTheDocument();

    await user.click(screen.getByRole("button", {name: "Close agent detail"}));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("keeps only the connections rail on the projects page", () => {
    render(<ProjectsView snapshot={registrySnapshot}/>);
    expect(screen.getByLabelText("2 known projects, 1 live")).toBeInTheDocument();
    expect(screen.queryByText("Installation")).not.toBeInTheDocument();
    expect(screen.getByText("Current runtime connections")).toBeInTheDocument();
    expect(screen.getByText("No peer connections are currently projected.")).toBeInTheDocument();
    expect(screen.queryByText("Disconnect")).not.toBeInTheDocument();
  });

  it("filters projects by name, identity, path, or status", async () => {
    const user = userEvent.setup();
    render(<ProjectsView snapshot={registrySnapshot}/>);

    await user.type(screen.getByRole("searchbox", {name: "Search projects"}), "idle");
    expect(screen.getByText("Idle project")).toBeInTheDocument();
    expect(screen.queryByText("Live project")).not.toBeInTheDocument();
  });

  it("shows network and termination controls for each projected connection", async () => {
    const user = userEvent.setup();
    const onOpenNetwork = vi.fn();
    const snapshot = {
      ...registrySnapshot,
      network: {
        peers: [{nodeId: "peer-a", sessionId: "session-a", liveness: "CONNECTED", usable: true, establishedAt: "2026-09-10T00:00:00Z", authenticated: true, health: "HEALTHY"}],
        routes: [{kind: "DIRECT", destinationNodeId: "peer-a", path: ["local", "peer-a"], nextHop: "", relayNodeId: ""}],
      },
    } as unknown as Snapshot;

    render(<ProjectsView snapshot={snapshot} onOpenNetwork={onOpenNetwork}/>);
    expect(screen.getByRole("button", {name: "View network"})).toBeInTheDocument();
    await user.click(screen.getByRole("button", {name: "Terminate connection to peer-a"}));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Do you want to terminate your end of the connection?")).toBeInTheDocument();
    expect(screen.getByText(/peer-to-peer connection/)).toBeInTheDocument();
    expect(screen.getByRole("button", {name: "Terminate end"})).toBeDisabled();
    await user.click(screen.getByRole("button", {name: "Cancel"}));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("opens connection details from a network row and removes the route widget", async () => {
    const user = userEvent.setup();
    const network = {
      status: "CONFIGURED",
      peers: [{nodeId: "peer-a", sessionId: "session-a", liveness: "CONNECTED", usable: true, establishedAt: "2026-09-10T00:00:00Z", authenticated: true, health: "HEALTHY"}],
      routes: [{kind: "DIRECT", destinationNodeId: "peer-a", path: ["local", "peer-a"], nextHop: "peer-a", relayNodeId: ""}],
      overlay: {status: "CONFIGURED", authorityNodeId: "peer-a", revision: 3, memberCount: 1, expiresAt: "2026-09-10T04:00:00Z", members: [{nodeId: "peer-a", status: "ACTIVE"}], directEdges: [], desiredEdges: []},
      relay: {status: "DISABLED", localAddress: "", activeConnections: 0, configured: false, connected: false, relayIdentity: "", authorized: false, activeRouteUsage: 0},
    } as unknown as Snapshot["network"];

    render(<NetworkView network={network} client={null}/>);
    expect(screen.queryByText("Server-selected routes")).not.toBeInTheDocument();

    await user.click(screen.getByRole("row", {name: /peer-a/}));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("heading", {name: "peer-a"})).toBeInTheDocument();
    expect(screen.getByText("local → peer-a")).toBeInTheDocument();

    await user.click(screen.getByRole("button", {name: "Close connection detail"}));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("connects a runtime-projected pending join through the existing client command", async () => {
    const user = userEvent.setup();
    const connect = vi.fn().mockResolvedValue({apiVersion: "v1", operationId: "join-op", state: "CONNECTED"});
    const client = {connect} as unknown as ControlPlaneClient;
    const network = {
      status: "UNCONFIGURED",
      peers: [],
      routes: [],
      overlay: {status: "UNCONFIGURED", authorityNodeId: "", revision: 0, memberCount: 0, expiresAt: "", members: [], directEdges: [], desiredEdges: []},
      relay: {status: "DISABLED", localAddress: "", activeConnections: 0, configured: false, connected: false, relayIdentity: "", authorized: false, activeRouteUsage: 0},
    } as unknown as Snapshot["network"];

    render(<NetworkView network={network} client={client} onboarding={{pendingJoins: [{operationId: "join-op", state: "WAITING_FOR_CONNECT", expiresAt: "2026-09-11T02:00:00Z"}]}}/>);

    await user.click(screen.getByRole("button", {name: "Complete Join"}));
    expect(connect).toHaveBeenCalledWith("join-op");
    expect(screen.getByText("Pending join connected through the local Link runtime.")).toBeInTheDocument();
  });

  it("uses Projects as the global route and parses project-local views", () => {
    expect(parseRoute("/")).toEqual({kind: "projects"});
    expect(parseRoute("/projects/proj_1/coordination")).toEqual({
      kind: "project",
      projectId: "proj_1",
      view: "coordination",
    });
    expect(routePath({kind: "project", projectId: "proj 1", view: "network"})).toBe("/projects/proj%201/network");
  });

  it("opens the diagnostics inspector as a modal and cycles findings in place", async () => {
    const user = userEvent.setup();
    render(<DiagnosticsView diagnostics={diagnostics}/>);

    await user.click(screen.getByRole("row", {name: /First finding/}));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("heading", {name: "First finding"})).toBeInTheDocument();
    expect(screen.getByRole("button", {name: "Previous finding"})).toBeDisabled();
    expect(screen.getByRole("button", {name: "Next finding"})).not.toBeDisabled();

    await user.click(screen.getByRole("button", {name: "Next finding"}));
    expect(screen.getByRole("heading", {name: "Second finding"})).toBeInTheDocument();
    expect(screen.getByRole("button", {name: "Previous finding"})).not.toBeDisabled();
    expect(screen.getByRole("button", {name: "Next finding"})).toBeDisabled();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("moves the project identity into the right rail and provides a Projects back button", async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    render(<ProjectHeader snapshot={{...emptySnapshot, project: {id: "proj_test", name: "Test", path: "C:\\projects\\test", createdAt: "2026-09-10T00:00:00Z"}} as Snapshot} title="Test" onBack={onBack}/>);

    expect(screen.getByRole("heading", {name: "Test"})).toBeInTheDocument();
    expect(screen.getByText("Project ID")).toBeInTheDocument();
    await user.click(screen.getByRole("button", {name: "Back to projects"}));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it.each(["INACTIVE", "UNAVAILABLE", "IDENTITY_MISMATCH"] as const)("provides an icon-only Projects back button for %s registry projects", async (status) => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    render(<RegistryProjectView project={{id: "proj_detail", name: "Detail project", path: "C:\\projects\\detail", createdAt: "2026-09-10T00:00:00Z", firstObservedAt: "2026-09-10T00:00:00Z", lastObservedAt: "2026-09-10T00:00:00Z", status}} onBack={onBack}/>);

    const back = screen.getByRole("button", {name: "Back to projects"});
    expect(back).toBeInTheDocument();
    await user.click(back);
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});
