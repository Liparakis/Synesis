import {render, screen, waitFor, within} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {vi} from "vitest";
import {App} from "./App";
import {attentionCount, isFirstRun, nextCommand, readinessChecks} from "./readiness";
import {ControlPlaneClient, ControlPlaneError, type DiagnosticFinding, type OperatorRequestSnapshot, type Snapshot} from "../api/controlPlane";
import {mockSnapshot} from "../dev/mockSnapshot";

const connected = {id: "claude", supportLevel: "SUPPORTED", status: "INSTALLED"};
const notConnected = {id: "codex", supportLevel: "SUPPORTED", status: "NOT_INSTALLED"};
const finding = (code: string, group: string, summary: string, fix: string): DiagnosticFinding => ({
  code, group, mark: "confirmed", fix, summary, severity: "WARNING", confidence: "CONFIRMED", explanation: summary, affectedResourceType: "Project", repairSupported: false, recommendation: "no_action",
});
const withFindings = (findings: DiagnosticFinding[]) => ({...mockSnapshot.diagnostics, findings});
/** A project nobody has worked in: the doctor says nothing is wrong and no agent or WorkGroup exists. */
const fresh = (overrides: Partial<Snapshot> = {}): Snapshot => ({
  ...mockSnapshot, agents: [], workgroups: [], claims: [], tasks: [], ownerships: [], disputes: [], trash: [], operatorRequests: [],
  providers: [connected, notConnected], diagnostics: withFindings([]),
  membership: {shared: false, owner: null, self: null, selfIsOwner: false, members: []},
  ...overrides,
});

afterEach(() => {
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/projects");
});

async function openLive(snapshot: Snapshot, view: string, wait: string | RegExp) {
  vi.spyOn(ControlPlaneClient.prototype, "resumeSession").mockResolvedValue(snapshot);
  vi.spyOn(ControlPlaneClient.prototype, "startEvents").mockReturnValue(() => undefined);
  window.history.replaceState(null, "", "/projects/" + mockSnapshot.project!.id + "/" + view);
  render(<App/>);
  await screen.findByText(wait);
}

describe("the readiness checks", () => {
  it("asks for an agent to be connected first, then for its task", () => {
    expect(nextCommand(fresh({providers: [notConnected]}))).toBe("synesis init");
    expect(nextCommand(fresh())).toBe('synesis agents run --agent claude --prompt "Implement X"');
    const checks = readinessChecks(fresh({providers: [notConnected]}));
    expect(checks.find((check) => check.id === "agents-connected")).toMatchObject({status: "attention", fix: "synesis init"});
    expect(checks.find((check) => check.id === "agents-found")?.status).toBe("pending");
    expect(attentionCount(checks)).toBe(1);
  });

  it("takes the doctor's own fix for an agent that is not on the computer and for a firewall that blocks Link", () => {
    const shared = {shared: true, owner: "node-a", self: "node-a", selfIsOwner: true, members: [
      {nodeId: "node-a", self: true, owner: true, state: "SELF", since: null, lastReceived: null},
      {nodeId: "node-b", self: false, owner: false, state: "DISCONNECTED", since: null, lastReceived: null},
    ]};
    const checks = readinessChecks(fresh({membership: shared, diagnostics: withFindings([
      finding("AGENT_NOT_ON_PATH", "environment", "claude is not on this computer's PATH", "Install claude"),
      finding("LINK_FIREWALL_MISSING", "network", "Link has no firewall rule", "Run the installer's firewall step"),
    ])}));

    expect(checks.find((check) => check.id === "agents-found")).toMatchObject({status: "attention", fix: "Install claude"});
    expect(checks.find((check) => check.id === "firewall")).toMatchObject({status: "attention", fix: "Run the installer's firewall step"});
    expect(checks.find((check) => check.id === "computers")).toMatchObject({status: "attention", detail: "1 of 1 other computer away."});
  });

  it("checks no firewall and no other computer for a project that runs on one computer", () => {
    const checks = readinessChecks(fresh());
    expect(checks.some((check) => check.id === "firewall")).toBe(false);
    expect(checks.find((check) => check.id === "computers")?.detail).toBe("This project runs on this computer only.");
  });

  it("counts operator requests as waiting for the person", () => {
    const request = {id: "opr_1", condition: "lane_asks_operator", raisedBy: "lane", summary: "Which license?", evidence: ""};
    expect(readinessChecks(fresh({operatorRequests: [request]})).find((check) => check.id === "operator")).toMatchObject({status: "attention", detail: "1 operator request waits for you."});
  });

  it("is a first run only while no agent and no WorkGroup exists", () => {
    expect(isFirstRun(fresh())).toBe(true);
    expect(isFirstRun(mockSnapshot)).toBe(false);
    expect(isFirstRun(fresh({workgroups: mockSnapshot.workgroups}))).toBe(false);
  });
});

describe("the first run view", () => {
  it("says what Synesis is, shows the checks and gives the one command to run when no agent is connected", async () => {
    await openLive(fresh({providers: [notConnected]}), "overview", "Welcome to Synesis");

    const view = screen.getByRole("region", {name: "First run"});
    expect(within(view).getByRole("heading", {name: /Several coding agents work in one repository/})).toBeInTheDocument();
    const checks = within(view).getByRole("list", {name: "Readiness checks"});
    expect(within(checks).getByText("No agent is connected to this project.")).toBeInTheDocument();
    expect(within(checks).getByText("Needs attention")).toBeInTheDocument();
    // Once as the fix of the failing check, once as the next command to run.
    expect(within(view).getAllByText("synesis init", {selector: "code"})).toHaveLength(2);
    expect(within(view).getByLabelText("Copy Next, run this in the project folder")).toBeInTheDocument();
    expect(screen.queryByText("No WorkGroups yet")).not.toBeInTheDocument();
  });

  it("names the task command once an agent is connected", async () => {
    await openLive(fresh(), "overview", "Welcome to Synesis");

    expect(screen.getByText('synesis agents run --agent claude --prompt "Implement X"', {selector: "code"})).toBeInTheDocument();
    expect(screen.getByText("claude connected to this project.")).toBeInTheDocument();
  });

  it("gives way to the overview as soon as an agent is known", async () => {
    await openLive({...fresh(), agents: mockSnapshot.agents}, "overview", "No WorkGroups yet");

    expect(screen.queryByText("Welcome to Synesis")).not.toBeInTheDocument();
    expect(within(screen.getByRole("list", {name: "Readiness checks"})).getByText("Agents at work")).toBeInTheDocument();
  });
});

describe("the always-available readiness panel", () => {
  it("shows how many checks need attention in the project rail and opens the same checks", async () => {
    const user = userEvent.setup();
    await openLive(fresh({providers: [notConnected]}), "agents", "No agents yet");

    const entry = screen.getByRole("button", {name: /Readiness/});
    expect(within(entry).getByLabelText("1 need attention")).toBeInTheDocument();
    await user.click(entry);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("No agent is connected to this project.")).toBeInTheDocument();
    expect(within(dialog).getByText("This project runs on this computer only.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", {name: "Close readiness"}));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("operator requests", () => {
  const asked: OperatorRequestSnapshot = {id: "opr_lane01", condition: "lane_asks_operator", raisedBy: "lane-1", summary: "Which license?", evidence: ""};
  const raised: OperatorRequestSnapshot = {id: "opr_synesis01", condition: "bound_consumer_absent", raisedBy: "", summary: "A consumer is absent", evidence: ""};
  const coordination = () => fresh({agents: mockSnapshot.agents, workgroups: mockSnapshot.workgroups, claims: mockSnapshot.claims, operatorRequests: [asked, raised]});

  async function openOperatorTab(user: ReturnType<typeof userEvent.setup>) {
    await openLive(coordination(), "coordination", "Refactor the parser safely");
    await user.click(within(screen.getByRole("navigation", {name: "Coordination records"})).getByRole("button", {name: "Operator"}));
  }

  it("is listed on the overview for the person to act on", async () => {
    await openLive(coordination(), "overview", "Needs you");

    const section = screen.getByRole("region", {name: "Operator requests"});
    expect(within(section).getByText("Which license?")).toBeInTheDocument();
    expect(within(section).getByRole("button", {name: "Recheck"})).toBeEnabled();
  });

  it("closes a request a lane asked and leaves the one Synesis raised to close itself", async () => {
    const close = vi.spyOn(ControlPlaneClient.prototype, "closeOperatorRequest").mockResolvedValue({state: "CLOSED"});
    const user = userEvent.setup();
    await openOperatorTab(user);

    expect(screen.queryByRole("button", {name: "Close opr_synesis01"})).not.toBeInTheDocument();
    expect(within(screen.getByRole("row", {name: /opr_synesis01/})).getByText("Closes by itself")).toBeInTheDocument();
    await user.click(screen.getByRole("button", {name: "Close opr_lane01"}));

    expect(close).toHaveBeenCalledWith("opr_lane01");
    expect(await screen.findByText("Closed opr_lane01.")).toBeInTheDocument();
  });

  it("rechecks and says what is still open", async () => {
    const recheck = vi.spyOn(ControlPlaneClient.prototype, "recheckOperatorRequests").mockResolvedValue({state: "blocked", reason: null, failed: false, openRequests: 2});
    const user = userEvent.setup();
    await openOperatorTab(user);

    await user.click(screen.getByRole("button", {name: "Recheck"}));

    expect(recheck).toHaveBeenCalledOnce();
    expect(await screen.findByText("Checked again. 2 requests are still open.")).toBeInTheDocument();
  });

  it("says when the check finds nothing left, and when the pass fails", async () => {
    const recheck = vi.spyOn(ControlPlaneClient.prototype, "recheckOperatorRequests")
      .mockResolvedValueOnce({state: "ok", reason: null, failed: false, openRequests: 0})
      .mockResolvedValueOnce({state: "failed", reason: "validation_failed", failed: true, openRequests: 1});
    const user = userEvent.setup();
    await openOperatorTab(user);

    await user.click(screen.getByRole("button", {name: "Recheck"}));
    expect(await screen.findByText("Checked again. Nothing is waiting for you.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", {name: "Recheck"}));
    expect(await screen.findByText("Checked again, but the pass failed (validation_failed).")).toBeInTheDocument();
    expect(recheck).toHaveBeenCalledTimes(2);
  });

  it("shows the refusal when the control plane will not close a request", async () => {
    vi.spyOn(ControlPlaneClient.prototype, "closeOperatorRequest").mockRejectedValue(new ControlPlaneError(409, "OPERATOR_REQUEST_NOT_CLOSED", "no open request has this identifier"));
    const user = userEvent.setup();
    await openOperatorTab(user);

    await user.click(screen.getByRole("button", {name: "Close opr_lane01"}));

    expect(await screen.findByText("no open request has this identifier")).toBeInTheDocument();
  });

  it("offers the actions only to a page that has a control-plane client", async () => {
    mockSnapshot.operatorRequests = [asked];
    try {
      window.history.replaceState(null, "", "/projects/" + mockSnapshot.project!.id + "/overview?mock=1");
      render(<App/>);
      await screen.findByText("Needs you");
      expect(screen.getByRole("button", {name: "Recheck"})).toBeDisabled();
      expect(screen.getByRole("button", {name: "Close opr_lane01"})).toBeDisabled();
    } finally {
      mockSnapshot.operatorRequests = [];
    }
  });
});

describe("the project's computers", () => {
  const owner = "sl1-4202b14fde83332a3f7223e907fb0d4f368847fc4b0e746ea314739ce94efe80";
  const away = "sl1-0e7d7bebb0ba5a8713f0bcccc061241ee24b2c5d11e208097c86e4836d20d2a3";
  const here = {nodeId: owner, self: true, owner: true, state: "SELF", since: null, lastReceived: null};
  const connectedMember = {nodeId: "sl1-aaaaaaaaaaaaaaaa0000000000000000", self: false, owner: false, state: "CONNECTED", since: "2026-10-20T10:00:00Z", lastReceived: "2026-10-20T10:01:00Z"};
  const awayMember = {nodeId: away, self: false, owner: false, state: "DISCONNECTED", since: null, lastReceived: null};
  const sharedBy = (selfIsOwner: boolean) => fresh({membership: {shared: true, owner, self: selfIsOwner ? owner : away, selfIsOwner, members: [here, connectedMember, awayMember]}});

  it("lists each computer as connected or away, with the owner marked", async () => {
    await openLive(sharedBy(true), "network", "Computers");

    const panel = screen.getByRole("region", {name: "Project computers"});
    expect(within(screen.getByRole("row", {name: /sl1-aaaaaaaa/})).getByText("Connected")).toBeInTheDocument();
    expect(within(screen.getByRole("row", {name: /sl1-0e7d7beb/})).getByText("Away")).toBeInTheDocument();
    expect(within(screen.getByRole("row", {name: /\(this computer\)/})).getByText("Owner")).toBeInTheDocument();
    expect(within(panel).getAllByText("Member")).toHaveLength(2);
  });

  it("says a project that is not shared runs on this computer only", async () => {
    await openLive(fresh(), "network", "Computers");

    expect(within(screen.getByRole("region", {name: "Project computers"})).getByText(/runs on this computer only/)).toBeInTheDocument();
  });

  it("offers removal to the owner only", async () => {
    await openLive(sharedBy(false), "network", "Computers");

    expect(screen.queryByRole("button", {name: /^Remove /})).not.toBeInTheDocument();
  });

  it("asks before it removes a computer, and only the confirmation removes it", async () => {
    const remove = vi.spyOn(ControlPlaneClient.prototype, "removeMember").mockResolvedValue({state: "REMOVED"});
    const user = userEvent.setup();
    await openLive(sharedBy(true), "network", "Computers");
    expect(screen.queryByRole("button", {name: "Remove sl1-4202b14f…"})).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", {name: "Remove sl1-0e7d7beb…"}));
    expect(remove).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/Its open work ends on every computer/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", {name: "Remove computer"}));

    expect(remove).toHaveBeenCalledWith(away);
    expect(await screen.findByText(/Removed sl1-0e7d7beb…/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("removes nobody when the person cancels", async () => {
    const remove = vi.spyOn(ControlPlaneClient.prototype, "removeMember").mockResolvedValue({state: "REMOVED"});
    const user = userEvent.setup();
    await openLive(sharedBy(true), "network", "Computers");

    await user.click(screen.getByRole("button", {name: "Remove sl1-0e7d7beb…"}));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", {name: "Cancel"}));

    expect(remove).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("explains a refusal in words", async () => {
    vi.spyOn(ControlPlaneClient.prototype, "removeMember").mockRejectedValue(new ControlPlaneError(409, "NOT_A_MEMBER", "the member cannot be removed: NOT_A_MEMBER"));
    const user = userEvent.setup();
    await openLive(sharedBy(true), "network", "Computers");

    await user.click(screen.getByRole("button", {name: "Remove sl1-0e7d7beb…"}));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", {name: "Remove computer"}));

    expect(await screen.findByText("That computer is not a member of this project any more.")).toBeInTheDocument();
  });
});
