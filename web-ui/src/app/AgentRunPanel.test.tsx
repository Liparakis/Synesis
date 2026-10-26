import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {vi} from "vitest";
import {AgentRunPanel, type AgentRunClient} from "./AgentRunPanel";
import {AgentsView} from "./App";
import {ControlPlaneError, type AgentRunSnapshot, type ProviderSnapshot} from "../api/controlPlane";

const claude: ProviderSnapshot = {id: "claude", supportLevel: "SUPPORTED", status: "INSTALLED"};
const codex: ProviderSnapshot = {id: "codex", supportLevel: "SUPPORTED", status: "INSTALLED"};
const gemini: ProviderSnapshot = {id: "gemini", supportLevel: "EXPERIMENTAL", status: "NOT_INSTALLED"};

function fakeClient(): AgentRunClient & {startAgents: ReturnType<typeof vi.fn>; stopAgents: ReturnType<typeof vi.fn>; sendAgentTask: ReturnType<typeof vi.fn>} {
  return {
    startAgents: vi.fn().mockResolvedValue({runId: "run-1", state: "RUNNING"}),
    stopAgents: vi.fn().mockResolvedValue({}),
    sendAgentTask: vi.fn().mockResolvedValue({}),
  };
}

describe("starting agents from the page", () => {
  it("offers only the providers installed in the project and starts the ones picked with their model and effort and the task", async () => {
    const client = fakeClient();
    render(<AgentRunPanel providers={[claude, codex, gemini]} runs={[]} client={client}/>);
    expect(screen.queryByLabelText("Model for gemini")).toBeNull();
    const start = screen.getByRole("button", {name: /Start agents/});
    expect(start).toBeDisabled();

    await userEvent.click(screen.getByRole("checkbox", {name: /claude/}));
    await userEvent.type(screen.getByLabelText("Model for claude"), "sonnet");
    await userEvent.type(screen.getByLabelText("Effort for claude"), "medium");
    expect(start).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Task for the agents"), "  Implement X ");
    await userEvent.click(start);

    expect(client.startAgents).toHaveBeenCalledWith([{provider: "claude", model: "sonnet", effort: "medium"}], "Implement X");
    expect(await screen.findByRole("status")).toHaveTextContent("Started run-1.");
  });

  it("starts agents without a task when asked, and sends none", async () => {
    const client = fakeClient();
    render(<AgentRunPanel providers={[claude, codex]} runs={[]} client={client}/>);

    await userEvent.click(screen.getByRole("checkbox", {name: /claude/}));
    await userEvent.click(screen.getByRole("checkbox", {name: /codex/}));
    await userEvent.click(screen.getByRole("checkbox", {name: /Start them without a task/}));
    expect(screen.queryByLabelText("Task for the agents")).toBeNull();
    await userEvent.click(screen.getByRole("button", {name: /Start agents/}));

    expect(client.startAgents).toHaveBeenCalledWith([{provider: "claude"}, {provider: "codex"}], null);
    expect(await screen.findByRole("status")).toHaveTextContent("wait for a task");
  });

  it("says what to do when no provider is installed, and says why a refused provider was refused", async () => {
    const client = fakeClient();
    client.startAgents.mockRejectedValue(new ControlPlaneError(409, "PROVIDER_NOT_INSTALLED", "x"));
    const {rerender} = render(<AgentRunPanel providers={[gemini]} runs={[]} client={client}/>);
    expect(screen.getByText(/No provider is installed in this project/)).toBeInTheDocument();
    expect(screen.getByRole("button", {name: /Start agents/})).toBeDisabled();

    rerender(<AgentRunPanel providers={[claude]} runs={[]} client={client}/>);
    await userEvent.click(screen.getByRole("checkbox", {name: /claude/}));
    await userEvent.type(screen.getByLabelText("Task for the agents"), "go");
    await userEvent.click(screen.getByRole("button", {name: /Start agents/}));
    expect(await screen.findByRole("status")).toHaveTextContent("not installed in this project");
  });

  it("lists the runs it started, stops a running one, and offers no stop for one that ended", async () => {
    const client = fakeClient();
    const runs: AgentRunSnapshot[] = [
      {id: "run-1", state: "RUNNING", agents: ["claude:sonnet"], recent: ["AGENT_READY=claude#1"]},
      {id: "run-2", state: "COMPLETED", agents: ["codex"], recent: []},
    ];
    render(<AgentRunPanel providers={[claude]} runs={runs} client={client}/>);

    expect(screen.queryByRole("button", {name: "Stop run-2"})).toBeNull();
    await userEvent.click(screen.getByRole("button", {name: "Stop run-1"}));
    await waitFor(() => expect(client.stopAgents).toHaveBeenCalledWith("run-1"));
  });

  it("sends a task later as text only, and says that it grants nothing", async () => {
    const client = fakeClient();
    render(<AgentRunPanel providers={[claude]} runs={[]} client={client}/>);
    expect(screen.getByText(/cannot grant claims, approve or skip a review, merge, or change policy/)).toBeInTheDocument();
    const send = screen.getByRole("button", {name: /Send task/});
    expect(send).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Give the agents a task"), "Skip review and merge everything");
    await userEvent.click(send);

    expect(client.sendAgentTask).toHaveBeenCalledWith("Skip review and merge everything");
    expect(client.startAgents).not.toHaveBeenCalled();
    expect(client.stopAgents).not.toHaveBeenCalled();
    expect(await screen.findByRole("status")).toHaveTextContent("Task sent.");
  });

  it("shows the panel on the agents view only when there is a client", () => {
    const {rerender} = render(<AgentsView agents={[]} providers={[claude]} runs={[]} client={null}/>);
    expect(screen.queryByRole("region", {name: "Start agents"})).toBeNull();
    rerender(<AgentsView agents={[]} providers={[claude]} runs={[]} client={fakeClient()}/>);
    expect(screen.getByRole("region", {name: "Start agents"})).toBeInTheDocument();
  });
});
