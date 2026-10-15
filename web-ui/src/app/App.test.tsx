import {render, screen, waitFor, within} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {AgentsView, App, DiagnosticsView, NetworkView, ProjectHeader, ProjectSelectionView, ProjectsView, RegistryProjectView} from "./App";
import {parseRoute, routePath} from "./routes";
import {ControlPlaneClient, ControlPlaneError, type AgentSnapshot, type ProjectSelection, type Snapshot} from "../api/controlPlane";
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

  it("keeps add and remove actions visible in the development preview", async () => {
    window.history.replaceState(null, "", "/projects?mock=1");
    render(<App/>);

    expect(await screen.findByRole("button", {name: "Add project"})).toBeInTheDocument();
    expect(screen.getByRole("button", {name: "Remove Vector mesh from registry"})).toBeInTheDocument();
    expect(screen.getByRole("button", {name: "Remove Test from registry"})).toBeInTheDocument();
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

  it("filters projects by an explicit registry status", async () => {
    const user = userEvent.setup();
    render(<ProjectsView snapshot={registrySnapshot}/>);

    await user.selectOptions(screen.getByRole("combobox", {name: "Filter projects by status"}), "LIVE");
    expect(screen.getByText("Live project")).toBeInTheDocument();
    expect(screen.queryByText("Idle project")).not.toBeInTheDocument();
  });

  it("registers a project from the main page without deleting project data", async () => {
    const user = userEvent.setup();
    const chooseProject = vi.fn().mockResolvedValue({state: "SELECTED", path: "C:\\Projects\\new-project", name: "new-project"});
    const registerProject = vi.fn().mockResolvedValue({state: "REGISTERED"});
    const refresh = vi.fn().mockResolvedValue(undefined);
    render(<ProjectsView snapshot={registrySnapshot} client={{chooseProject, registerProject} as unknown as ControlPlaneClient} onSnapshotRefresh={refresh}/>);

    await user.click(screen.getByRole("button", {name: "Add project"}));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", {name: "Choose project folder"}));
    await user.click(within(dialog).getByRole("button", {name: "Add project"}));

    expect(registerProject).toHaveBeenCalledWith("C:\\Projects\\new-project");
    expect(refresh).toHaveBeenCalled();
  });

  it("uses the native folder picker and keeps the folder-derived name read-only", async () => {
    const user = userEvent.setup();
    const chooseProject = vi.fn().mockResolvedValue({state: "SELECTED", path: "C:\\Projects\\Copenhagen Trip", name: "Copenhagen Trip"});
    const registerProject = vi.fn().mockResolvedValue({state: "REGISTERED"});
    const client = {chooseProject, registerProject} as unknown as ControlPlaneClient;
    render(<ProjectsView snapshot={registrySnapshot} client={client}/>);

    await user.click(screen.getByRole("button", {name: "Add project"}));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("button", {name: "Choose project folder"})).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", {name: "Choose project folder"}));

    expect(chooseProject).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Copenhagen Trip")).toBeInTheDocument();
    expect(within(dialog).queryByRole("textbox", {name: "Project name"})).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", {name: "Add project"}));
    expect(registerProject).toHaveBeenCalledWith("C:\\Projects\\Copenhagen Trip");
  });

  it("shows folder initialization failures inside a dismissible error panel", async () => {
    const user = userEvent.setup();
    const chooseProject = vi.fn().mockResolvedValue({state: "SELECTED", path: "C:\\Projects\\blocked", name: "blocked"});
    const registerProject = vi.fn().mockRejectedValue({code: "PROJECT_INITIALIZATION_FAILED"});
    const client = {chooseProject, registerProject} as unknown as ControlPlaneClient;
    render(<ProjectsView snapshot={registrySnapshot} client={client}/>);

    await user.click(screen.getByRole("button", {name: "Add project"}));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", {name: "Choose project folder"}));
    await user.click(within(dialog).getByRole("button", {name: "Add project"}));

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Synesis could not initialize that folder.");
    expect(alert.firstElementChild?.tagName).toBe("P");
    expect(alert.lastElementChild).toBe(within(alert).getByRole("button", {name: "Dismiss project folder error"}));
    await user.click(within(alert).getByRole("button", {name: "Dismiss project folder error"}));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("removes one registry entry after a confirmation, the current project included", async () => {
    const user = userEvent.setup();
    const removeProjects = vi.fn().mockResolvedValue({state: "REMOVED"});
    const client = {removeProjects} as unknown as ControlPlaneClient;
    render(<ProjectsView snapshot={registrySnapshot} client={client}/>);

    await user.click(screen.getByRole("button", {name: "Remove Idle project from registry"}));
    expect(screen.getByRole("dialog")).not.toHaveTextContent("serving this page");
    await user.click(screen.getByRole("button", {name: "Remove from registry"}));
    expect(removeProjects).toHaveBeenCalledWith(["proj_idle"]);

    await user.click(screen.getByRole("button", {name: "Remove Live project from registry"}));
    expect(screen.getByRole("dialog")).toHaveTextContent("Live project is serving this page");
    await user.click(screen.getByRole("button", {name: "Remove from registry"}));
    await waitFor(() => expect(removeProjects).toHaveBeenCalledWith(["proj_live"]));
  });

  it("hides a project immediately while registry removal is pending", async () => {
    const user = userEvent.setup();
    let resolveRemoval: (value: Record<string, unknown>) => void = () => undefined;
    const removeProjects = vi.fn(() => new Promise<Record<string, unknown>>((resolve) => {
      resolveRemoval = resolve;
    }));
    render(<ProjectsView snapshot={registrySnapshot} client={{removeProjects} as unknown as ControlPlaneClient}/>);

    await user.click(screen.getByRole("button", {name: "Remove Idle project from registry"}));
    await user.click(screen.getByRole("button", {name: "Remove from registry"}));

    expect(screen.queryByText("Idle project")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    resolveRemoval({state: "REMOVED"});
    await waitFor(() => expect(removeProjects).toHaveBeenCalledWith(["proj_idle"]));
  });

  it("restores an optimistically removed project when registry removal fails", async () => {
    const user = userEvent.setup();
    let rejectRemoval: (reason?: unknown) => void = () => undefined;
    const removeProjects = vi.fn(() => new Promise<Record<string, unknown>>((_, reject) => {
      rejectRemoval = reject;
    }));
    render(<ProjectsView snapshot={registrySnapshot} client={{removeProjects} as unknown as ControlPlaneClient}/>);

    await user.click(screen.getByRole("button", {name: "Remove Idle project from registry"}));
    await user.click(screen.getByRole("button", {name: "Remove from registry"}));
    expect(screen.queryByText("Idle project")).not.toBeInTheDocument();

    rejectRemoval({code: "PROJECT_NOT_FOUND"});
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("no longer in the registry"));
    expect(screen.getByText("Idle project")).toBeInTheDocument();
  });

  it("removes several selected projects, of any status, after one confirmation", async () => {
    const user = userEvent.setup();
    const many = {
      ...registrySnapshot,
      knownProjects: [
        ...registrySnapshot.knownProjects,
        {id: "proj_old", name: "Old project", path: "C:\\projects\\old", createdAt: "2026-09-07T00:00:00Z", firstObservedAt: "2026-09-07T00:00:00Z", lastObservedAt: "2026-09-07T00:00:00Z", status: "UNAVAILABLE"},
        {id: "proj_kept", name: "Kept project", path: "C:\\projects\\kept", createdAt: "2026-09-06T00:00:00Z", firstObservedAt: "2026-09-06T00:00:00Z", lastObservedAt: "2026-09-06T00:00:00Z", status: "INACTIVE"},
      ],
    } as unknown as Snapshot;
    let refuse: (reason?: unknown) => void = () => undefined;
    const removeProjects = vi.fn().mockImplementationOnce(() => new Promise<Record<string, unknown>>(() => undefined)).mockImplementationOnce(() => new Promise<Record<string, unknown>>((_, reject) => {
      refuse = reject;
    }));
    render(<ProjectsView snapshot={many} client={{removeProjects} as unknown as ControlPlaneClient}/>);

    expect(screen.queryByRole("button", {name: "Remove selected"})).not.toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", {name: "Select all"}));
    expect(screen.getByRole("checkbox", {name: "Select Live project"})).toBeChecked();
    await user.click(screen.getByRole("checkbox", {name: "Select Kept project"}));
    expect(screen.getByRole("status")).toHaveTextContent("3 selected");
    expect(removeProjects).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", {name: "Remove selected"}));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Live project", {selector: "li"})).toBeInTheDocument();
    expect(within(dialog).getByText("Old project")).toBeInTheDocument();
    expect(within(dialog).queryByText("Kept project")).not.toBeInTheDocument();
    expect(removeProjects).not.toHaveBeenCalled();

    // One request names the whole selection, and the rows are gone before it answers.
    await user.click(within(dialog).getByRole("button", {name: "Remove 3 projects"}));
    expect(removeProjects).toHaveBeenCalledTimes(1);
    expect(removeProjects).toHaveBeenCalledWith(["proj_live", "proj_idle", "proj_old"]);
    expect(screen.queryByText("Idle project")).not.toBeInTheDocument();
    expect(screen.getByText("Kept project")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    // A refused removal brings its rows back and says why.
    await user.click(screen.getByRole("button", {name: "Remove Kept project from registry"}));
    await user.click(screen.getByRole("button", {name: "Remove from registry"}));
    expect(screen.queryByText("Kept project")).not.toBeInTheDocument();
    refuse({code: "REGISTRY_LOCK_FAILED", message: "could not access known project registry"});
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(screen.getByText("Kept project")).toBeInTheDocument();
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

  it("shows the joiner its answer link and lets the inviter use one", async () => {
    const user = userEvent.setup();
    const join = vi.fn().mockResolvedValue({apiVersion: "v1", operationId: "join-op", answerUri: "synesis://answer/SLA3-abc", peerIdentity: "sl1-host", expiresAt: "2026-09-11T02:00:00Z", state: "CONNECTING"});
    const invite = vi.fn().mockResolvedValue({apiVersion: "v1", operationId: "host-op", inviteUri: "synesis://join/SLB3-xyz", peerIdentity: "sl1-me", expiresAt: "2026-09-11T02:00:00Z", state: "WAITING_FOR_CONNECTION"});
    const answer = vi.fn().mockResolvedValue({apiVersion: "v1", operationId: "host-op", state: "SENDING_TO_JOINER"});
    const client = {join, invite, answer} as unknown as ControlPlaneClient;
    const network = {
      status: "UNCONFIGURED",
      peers: [],
      routes: [],
      overlay: {status: "UNCONFIGURED", authorityNodeId: "", revision: 0, memberCount: 0, expiresAt: "", members: [], directEdges: [], desiredEdges: []},
      relay: {status: "DISABLED", localAddress: "", activeConnections: 0, configured: false, connected: false, relayIdentity: "", authorized: false, activeRouteUsage: 0},
    } as unknown as Snapshot["network"];

    render(<NetworkView network={network} client={client}/>);

    await user.type(screen.getByLabelText("Join from an invitation"), "synesis://join/SLB3-xyz");
    await user.click(screen.getByRole("button", {name: "Join"}));
    expect(join).toHaveBeenCalledWith("synesis://join/SLB3-xyz");
    expect(screen.getByText("synesis://answer/SLA3-abc")).toBeInTheDocument();

    expect(screen.queryByLabelText("Answer from the other computer")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", {name: "Create Invitation"}));
    await user.type(screen.getByLabelText("Answer from the other computer"), "synesis://answer/SLA3-def");
    await user.click(screen.getByRole("button", {name: "Use Answer"}));
    expect(answer).toHaveBeenCalledWith("host-op", "synesis://answer/SLA3-def");
    expect(screen.getByText("Answer accepted. This computer is now reaching out to the other one.")).toBeInTheDocument();
  });

  it("shows a version mismatch loudly instead of a generic failure", async () => {
    const user = userEvent.setup();
    const message = "This invitation comes from a different Synesis version (compatibility 3; this computer has 4). The other computer is older: ask its owner to update Synesis, then ask for a new invitation.";
    const join = vi.fn().mockRejectedValue(new ControlPlaneError(400, "INCOMPATIBLE_VERSION", message));
    const client = {join} as unknown as ControlPlaneClient;
    const network = {
      status: "UNCONFIGURED",
      peers: [],
      routes: [],
      overlay: {status: "UNCONFIGURED", authorityNodeId: "", revision: 0, memberCount: 0, expiresAt: "", members: [], directEdges: [], desiredEdges: []},
      relay: {status: "DISABLED", localAddress: "", activeConnections: 0, configured: false, connected: false, relayIdentity: "", authorized: false, activeRouteUsage: 0},
    } as unknown as Snapshot["network"];

    render(<NetworkView network={network} client={client}/>);

    await user.type(screen.getByLabelText("Join from an invitation"), "synesis://join/SLB3-xyz");
    await user.click(screen.getByRole("button", {name: "Join"}));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
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

  it("moves the project views into the rail once a live project is open", async () => {
    const user = userEvent.setup();
    window.history.replaceState(null, "", "/projects?mock=1");
    render(<App/>);

    await screen.findByRole("button", {name: "Add project"});
    expect(screen.queryByRole("navigation", {name: "Project views"})).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Mock local state");

    await user.click(screen.getByRole("button", {name: "Open project"}));
    const views = screen.getByRole("navigation", {name: "Project views"});
    expect(within(views).getByRole("button", {name: "Overview"})).toHaveAttribute("aria-current", "page");

    await user.click(within(views).getByRole("button", {name: "Diagnostics"}));
    expect(screen.getByRole("heading", {name: "Diagnostics"})).toBeInTheDocument();
    expect(within(views).getByRole("button", {name: "Diagnostics"})).toHaveAttribute("aria-current", "page");
    expect(within(views).getByRole("button", {name: "Overview"})).not.toHaveAttribute("aria-current");

    await user.click(screen.getByRole("button", {name: "Back to projects"}));
    expect(screen.queryByRole("navigation", {name: "Project views"})).not.toBeInTheDocument();
  });
});
