import {useState} from "react";
import {Play, Send, Square} from "lucide-react";
import type {AgentChoice, AgentRunSnapshot, ControlPlaneClient, ProviderSnapshot} from "../api/controlPlane";
import {ControlPlaneError} from "../api/controlPlane";
import {StatusBadge, TableEmpty} from "./ui";

export type AgentRunClient = Pick<ControlPlaneClient, "startAgents" | "stopAgents" | "sendAgentTask">;

interface Choice {
  on: boolean;
  model: string;
  effort: string;
}

function failure(error: unknown): string {
  if (error instanceof ControlPlaneError && error.code === "PROVIDER_NOT_INSTALLED") return "That provider is not installed in this project. Run synesis init to connect it.";
  if (error instanceof ControlPlaneError && error.code === "AGENT_RUN_LIMIT") return "Four sets of agents already run here. Stop one first.";
  return error instanceof Error ? error.message : "That did not work.";
}

/**
 * Starts and stops agents from the page, and gives them a task. The page can name only a provider installed in the project,
 * a model, an effort and a task; the project's runtime starts them with the runner `synesis agents run` uses. A task is
 * text for the agents and nothing more: it cannot grant a claim, approve or skip a review, merge, or change policy.
 */
export function AgentRunPanel({providers, runs, client}: {providers: ProviderSnapshot[]; runs: AgentRunSnapshot[]; client: AgentRunClient}) {
  const installed = providers.filter((provider) => provider.status === "INSTALLED");
  const [choices, setChoices] = useState<Record<string, Choice>>({});
  const [waiting, setWaiting] = useState(false);
  const [task, setTask] = useState("");
  const [later, setLater] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const choice = (id: string): Choice => choices[id] ?? {on: false, model: "", effort: ""};
  const change = (id: string, patch: Partial<Choice>) => setChoices((current) => ({...current, [id]: {...choice(id), ...patch}}));
  const chosen: AgentChoice[] = installed.filter((provider) => choice(provider.id).on).map((provider) => {
    const {model, effort} = choice(provider.id);
    return {provider: provider.id, ...(model.trim() ? {model: model.trim()} : {}), ...(effort.trim() ? {effort: effort.trim()} : {})};
  });
  const run = async (action: () => Promise<string>) => {
    if (busy) return;
    setBusy(true);
    try {
      setMessage(await action());
    } catch (error) {
      setMessage(failure(error));
    } finally {
      setBusy(false);
    }
  };
  const start = () => run(async () => {
    const started = await client.startAgents(chosen, waiting ? null : task.trim());
    setTask("");
    return waiting ? "Started " + started.runId + ". The agents are ready and wait for a task." : "Started " + started.runId + ".";
  });
  const stop = (id: string) => run(async () => {
    await client.stopAgents(id);
    return "Stopping " + id + ".";
  });
  const send = () => run(async () => {
    await client.sendAgentTask(later.trim());
    setLater("");
    return "Task sent. Agents that wait for a task start with it; the others take it up when they next stop.";
  });
  return (
    <section className="agent-runs panel" aria-label="Start agents">
      <div className="form-block">
        <span className="form-label">Start agents</span>
        {installed.length === 0 ? <p className="muted">No provider is installed in this project. Run synesis init to connect the agents on this computer.</p> : (
          <div className="view-stack">
            {installed.map((provider) => (
              <div className="inline-form" key={provider.id}>
                <label><input type="checkbox" checked={choice(provider.id).on} onChange={(event) => change(provider.id, {on: event.target.checked})}/> {provider.id}</label>
                <input className="text-field" aria-label={"Model for " + provider.id} placeholder="Model (provider default)" value={choice(provider.id).model} onChange={(event) => change(provider.id, {model: event.target.value})}/>
                <input className="text-field" aria-label={"Effort for " + provider.id} placeholder="Effort (provider default)" value={choice(provider.id).effort} onChange={(event) => change(provider.id, {effort: event.target.value})}/>
              </div>
            ))}
          </div>
        )}
        <label className="muted"><input type="checkbox" checked={waiting} onChange={(event) => setWaiting(event.target.checked)}/> Start them without a task: they wait until you send one.</label>
        {!waiting && <textarea className="text-field" aria-label="Task for the agents" placeholder="What should the agents work on?" rows={3} value={task} onChange={(event) => setTask(event.target.value)}/>}
        <div className="inline-form">
          <button className="button button-primary" type="button" disabled={busy || chosen.length === 0 || (!waiting && !task.trim())} onClick={() => void start()}><Play size={15}/> Start agents</button>
        </div>
      </div>
      <div className="data-table-wrap">
        <table className="data-table"><thead><tr><th>Started here</th><th>State</th><th>Agents</th><th>Latest</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>{runs.length === 0 ? <TableEmpty colSpan={5} text="No agents were started from this page."/> : runs.map((item) => (
            <tr key={item.id}>
              <td className="mono">{item.id}</td>
              <td><StatusBadge value={item.state === "RUNNING" ? "ACTIVE" : item.state} label={item.state}/></td>
              <td>{item.agents.join(", ")}</td>
              <td className="table-cell-wrap">{item.recent.length ? item.recent[item.recent.length - 1] : "—"}</td>
              <td className="table-action-cell">{item.state === "RUNNING" && <button className="button button-secondary" type="button" disabled={busy} aria-label={"Stop " + item.id} onClick={() => void stop(item.id)}><Square size={15}/> Stop</button>}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      <div className="form-block">
        <label className="form-label" htmlFor="agent-task-later">Give the agents a task</label>
        <p className="muted">Agents that wait for a task start with it; agents already working are told it replaces their task. It is only text for them: it cannot grant claims, approve or skip a review, merge, or change policy. They still do all of that through Synesis.</p>
        <textarea className="text-field" id="agent-task-later" rows={3} value={later} onChange={(event) => setLater(event.target.value)}/>
        <div className="inline-form">
          <button className="button button-secondary" type="button" disabled={busy || !later.trim()} onClick={() => void send()}><Send size={15}/> Send task</button>
        </div>
      </div>
      {message && <p className="action-message" role="status">{message}</p>}
    </section>
  );
}
