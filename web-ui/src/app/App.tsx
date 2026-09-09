import {type ReactNode, useEffect, useMemo, useRef, useState} from "react";
import type {LucideIcon} from "lucide-react";
import {
  Activity,
  AlertTriangle,
  Boxes,
  Check,
  ChevronRight,
  CircleDot,
  Clipboard,
  Cpu,
  GitBranch,
  LayoutDashboard,
  Menu,
  Network,
  Radio,
  RefreshCw,
  Server,
  Settings2,
  ShieldCheck,
  TerminalSquare,
  Users,
} from "lucide-react";
import {
  type AgentSnapshot,
  consumeBootstrapToken,
  ControlPlaneClient,
  ControlPlaneError,
  type DiagnosticFinding,
  type NetworkSnapshot,
  type Snapshot,
  type WorkGroupSnapshot,
} from "../api/controlPlane";

type View = "dashboard" | "projects" | "agents" | "workgroups" | "network" | "diagnostics";

const navigation: Array<{ id: View; label: string; icon: LucideIcon }> = [
  {id: "dashboard", label: "Dashboard", icon: LayoutDashboard},
  {id: "projects", label: "Projects", icon: Boxes},
  {id: "agents", label: "Agents", icon: Users},
  {id: "workgroups", label: "WorkGroups", icon: GitBranch},
  {id: "network", label: "Peers & network", icon: Network},
  {id: "diagnostics", label: "Diagnostics", icon: ShieldCheck},
];

export function App() {
  const [view, setView] = useState<View>("dashboard");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [connection, setConnection] = useState<"CONNECTING" | "LIVE" | "DEGRADED" | "OFFLINE">("CONNECTING");
  const [message, setMessage] = useState("Connecting to the local control plane…");
  const clientRef = useRef<ControlPlaneClient | null>(null);
  const refreshTimer = useRef<number | undefined>(undefined);

  useEffect(() => {
    const client = new ControlPlaneClient();
    clientRef.current = client;
    let stopEvents: (() => void) | undefined;
    let disposed = false;

    const refresh = async () => {
      try {
        const next = await client.snapshot();
        if (!disposed) {
          setSnapshot(next);
          setConnection("LIVE");
          setMessage("Live local state");
        }
      } catch (error) {
        if (!disposed) setConnection("DEGRADED");
        if (!disposed) setMessage(errorMessage(error));
      }
    };

    const scheduleRefresh = () => {
      if (refreshTimer.current !== undefined) return;
      refreshTimer.current = window.setTimeout(() => {
        refreshTimer.current = undefined;
        void refresh();
      }, 180);
    };

    const start = async () => {
      try {
        const bootstrap = consumeBootstrapToken();
        if (!bootstrap) throw new Error("Open Synesis with the supported `synesis ui` command.");
        const initial = await client.startSession(bootstrap);
        if (disposed) return;
        setSnapshot(initial);
        setConnection("LIVE");
        setMessage("Live local state");
        stopEvents = client.startEvents({
          onSnapshot: (next) => {
            setSnapshot(next);
            setConnection("LIVE");
            setMessage("Live local state");
          },
          onEvent: (event) => {
            if (event.type === "refresh_required") {
              setConnection("DEGRADED");
              setMessage("Resynchronizing authoritative state…");
            }
            scheduleRefresh();
          },
          onError: (error) => {
            setConnection("DEGRADED");
            setMessage(errorMessage(error));
          },
        });
      } catch (error) {
        setConnection("OFFLINE");
        setMessage(errorMessage(error));
      }
    };

    void start();
    return () => {
      disposed = true;
      stopEvents?.();
      if (refreshTimer.current !== undefined) window.clearTimeout(refreshTimer.current);
    };
  }, []);

  const pageTitle = useMemo(() => navigation.find((item) => item.id === view)?.label ?? "Dashboard", [view]);

  if (!snapshot) {
    return <ConnectionScreen state={connection} message={message}/>;
  }

  return (
      <div className="app-shell">
        <aside className="sidebar">
          <div className="brand-lockup">
            <div className="brand-mark" aria-hidden="true"><CircleDot size={18} strokeWidth={2.5}/>
            </div>
            <div>
              <div className="brand-name">SYNESIS</div>
              <div className="brand-caption">local coordination</div>
            </div>
          </div>
          <nav className="primary-nav" aria-label="Primary navigation">
            <div className="nav-label">Workspace</div>
            {navigation.map(({id, label, icon: Icon}) => (
                <button key={id} className={`nav-item ${view === id ? "is-active" : ""}`}
                        onClick={() => setView(id)}>
                  <Icon size={17} aria-hidden="true"/>
                  <span>{label}</span>
                  {view === id &&
                      <ChevronRight className="nav-caret" size={15} aria-hidden="true"/>}
                </button>
            ))}
          </nav>
          <div className="sidebar-footer">
            <div className="sidebar-footer-line"><TerminalSquare size={15}/> localhost runtime</div>
            <div className="sidebar-footer-detail">API v1 ·
              seq {snapshot.runtime.headSequence}</div>
          </div>
        </aside>

        <main className="main-content">
          <header className="topbar">
            <div className="mobile-brand"><CircleDot size={17}/> SYNESIS</div>
            <div className="breadcrumb"><span>Workspace</span><ChevronRight
                size={14}/><strong>{pageTitle}</strong></div>
            <div className="topbar-actions">
              <ConnectionBadge state={connection} message={message}/>
              <button className="icon-button" aria-label="Refresh snapshot" title="Refresh snapshot"
                      onClick={() => window.location.reload()}>
                <RefreshCw size={16}/>
              </button>
              <button className="icon-button mobile-menu"
                      aria-label="Navigation is available in the sidebar"><Menu size={17}/></button>
            </div>
          </header>

          <div className="content-wrap">
            {view === "dashboard" && <DashboardView snapshot={snapshot} onNavigate={setView}/>}
            {view === "projects" && <ProjectsView snapshot={snapshot}/>}
            {view === "agents" && <AgentsView agents={snapshot.agents}/>}
            {view === "workgroups" && <WorkGroupsView workgroups={snapshot.workgroups}/>}
            {view === "network" &&
                <NetworkView network={snapshot.network} client={clientRef.current}/>}
            {view === "diagnostics" && <DiagnosticsView diagnostics={snapshot.diagnostics}/>}
          </div>
        </main>
      </div>
  );
}

function DashboardView({snapshot, onNavigate}: {
  snapshot: Snapshot;
  onNavigate(view: View): void
}) {
  const projectName = snapshot.project?.name ?? "No project initialized";
  const status = snapshot.diagnostics.overallStatus ?? "UNKNOWN";
  return (
      <div className="page-stack">
        <PageHeader eyebrow="LOCAL CONTROL PLANE" title={projectName}
                    description="Authoritative Synesis state, in one place." action={
          <button className="button button-secondary" onClick={() => onNavigate("network")}><Network
              size={16}/> View network</button>
        }/>
        <section className="dashboard-hero">
          <div>
            <div className="eyebrow">Runtime overview</div>
            <h2>{status === "HEALTHY" ? "Everything is operating normally." : "Attention is needed."}</h2>
            <p>{snapshot.project ? `Serving ${snapshot.project.name} from the local Synesis runtime.` : "Initialize a Git project to start using Synesis."}</p>
          </div>
          <StatusBadge value={status} large/>
        </section>
        <section className="metric-grid" aria-label="Runtime summary">
          <MetricCard label="Active projects" value={snapshot.runtime.activeProjectCount}
                      detail="served locally" icon={Boxes}/>
          <MetricCard label="Agents" value={snapshot.runtime.activeAgentCount}
                      detail="known participants" icon={Users}/>
          <MetricCard label="Connected peers" value={snapshot.runtime.connectedPeerCount}
                      detail="authenticated sessions" icon={Radio}/>
          <MetricCard label="WorkGroups" value={snapshot.workgroups.length}
                      detail="in the projection" icon={GitBranch}/>
        </section>
        <div className="two-column-grid">
          <section className="panel panel-featured">
            <PanelHeading icon={Activity} title="Network posture"
                          action={<button className="text-button"
                                          onClick={() => onNavigate("network")}>Open
                            network <ChevronRight size={14}/></button>}/>
            <div className="posture-row"><StatusBadge value={snapshot.network.status} large/>
              <div>
                <strong>{networkHeadline(snapshot.network)}</strong><span>{networkDetail(snapshot.network)}</span>
              </div>
            </div>
            <div className="posture-rule"/>
            <div className="mini-stat-row"><MiniStat label="Overlay"
                                                     value={snapshot.network.overlay.status}/><MiniStat
                label="Relay" value={snapshot.network.relay.status}/><MiniStat label="Sequence"
                                                                               value={String(snapshot.runtime.headSequence)}/>
            </div>
          </section>
          <section className="panel">
            <PanelHeading icon={Settings2} title="Recent diagnostics"
                          action={<button className="text-button"
                                          onClick={() => onNavigate("diagnostics")}>See
                            all <ChevronRight size={14}/></button>}/>
            {snapshot.diagnostics.findings.length === 0 ?
                <InlineEmpty text="No diagnostic findings reported."/> :
                <FindingList findings={snapshot.diagnostics.findings.slice(0, 3)}/>}
          </section>
        </div>
      </div>
  );
}

export function ProjectsView({snapshot}: { snapshot: Snapshot }) {
  const projects = snapshot.knownProjects ?? [];
  return (
      <div className="page-stack">
        <PageHeader eyebrow="PROJECTS" title="Projects"
                    description="Projects this Synesis installation has legitimately encountered."/>
        {projects.length > 0 ? <div className="card-list">{projects.map((project) => {
          const active = project.id === snapshot.project?.id;
          return <section className="project-card panel" key={project.id}>
              <div className="project-icon"><Boxes size={22}/></div>
              <div className="project-body">
                <div className="card-kicker">{active ? "ACTIVE PROJECT" : "KNOWN PROJECT"}</div>
                <h2>{project.name}</h2><p className="mono subdued">{project.id}</p>
                <div className="project-meta"><span><Server
                    size={14}/> {project.path}</span>{active && <><span><Users
                    size={14}/> {snapshot.agents.length} participants</span><span><GitBranch
                    size={14}/> {snapshot.workgroups.length} WorkGroups</span></>}</div>
              </div>
              <StatusBadge value={project.status}/></section>;
        })}</div> :
            <EmptyState icon={Boxes} title="No project initialized"
                        text="Initialize a Git project with Synesis to see it here."/>}
      </div>
  );
}

export function AgentsView({agents}: { agents: AgentSnapshot[] }) {
  return (
      <div className="page-stack">
        <PageHeader eyebrow="PARTICIPANTS" title="Agents"
                    description="Real workers projected from Synesis coordination state."/>
        {agents.length === 0 ? <EmptyState icon={Users} title="No agents yet"
                                           text="Agents will appear here when they announce work to the local coordination plane."/> :
            <div className="card-list">{agents.map((agent) => <AgentCard key={agent.id}
                                                                         agent={agent}/>)}</div>}
      </div>
  );
}

function AgentCard({agent}: { agent: AgentSnapshot }) {
  return <article className="panel agent-card">
    <div className="avatar"><Cpu size={17}/></div>
    <div className="agent-main">
      <div className="row-between">
        <div><h3>{agent.id}</h3><p>{agent.goal || "No declared goal"}</p></div>
        <StatusBadge value={agent.state}/></div>
      <div className="agent-meta"><span
          className="mono">{agent.provider || "Unknown provider"}</span><span>{agent.currentWork?.status ?? "No active work"}</span><span>{agent.waitingOn.length ? `Waiting on ${agent.waitingOn.length}` : "No dependency wait"}</span>
      </div>
    </div>
  </article>;
}

function WorkGroupsView({workgroups}: { workgroups: WorkGroupSnapshot[] }) {
  return <div className="page-stack"><PageHeader eyebrow="COORDINATION" title="WorkGroups"
                                                 description="Durable collaboration groups and their current state."/>{workgroups.length === 0 ?
      <EmptyState icon={GitBranch} title="No WorkGroups"
                  text="A WorkGroup will appear when coordinated work is created."/> :
      <div className="table-wrap panel">
        <table>
          <thead>
          <tr>
            <th>WorkGroup</th>
            <th>Status</th>
            <th>Participants</th>
            <th>Version</th>
          </tr>
          </thead>
          <tbody>{workgroups.map((group) => <tr key={group.id}>
            <td><strong>{group.goal || "Untitled WorkGroup"}</strong><span
                className="mono table-secondary">{group.id}</span></td>
            <td><StatusBadge value={group.status}/></td>
            <td>{group.participants.length ? group.participants.join(", ") : "No participants"}</td>
            <td className="mono">{group.version}</td>
          </tr>)}</tbody>
        </table>
      </div>}</div>;
}

function NetworkView({network, client}: {
  network: NetworkSnapshot;
  client: ControlPlaneClient | null
}) {
  const [inviteUri, setInviteUri] = useState("");
  const [expectedPeer, setExpectedPeer] = useState("");
  const [joinUri, setJoinUri] = useState("");
  const [joinResult, setJoinResult] = useState<{
    operationId: string;
    answerUri: string
  } | null>(null);
  const [answerOperation, setAnswerOperation] = useState("");
  const [answerUri, setAnswerUri] = useState("");
  const [actionMessage, setActionMessage] = useState("");
  const [busy, setBusy] = useState(false);

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setActionMessage("");
    try {
      await operation();
    } catch (error) {
      setActionMessage(errorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  return <div className="page-stack"><PageHeader eyebrow="LINK / OVERLAY" title="Peers & network"
                                                 description="Authenticated adjacency and server-reported routes. No client-side route inference."/>
    {network.overlay.status === "UNCONFIGURED" &&
        <div className="notice notice-warning"><AlertTriangle size={18}/>
          <div><strong>Network not configured</strong><span>Membership authority is not configured for this runtime. Synesis is showing the truthful physical and relay state without inventing overlay membership.</span>
          </div>
        </div>}
    <div className="network-summary-grid">
      <section className="panel network-summary">
        <div className="card-kicker">RUNTIME NETWORK</div>
        <div className="network-status-line"><StatusBadge value={network.status}
                                                          large/><span>{network.peers.length} authenticated peer{network.peers.length === 1 ? "" : "s"}</span>
        </div>
        <div className="mini-stat-row"><MiniStat label="Overlay"
                                                 value={network.overlay.status}/><MiniStat
            label="Members" value={String(network.overlay.memberCount)}/><MiniStat label="Relay"
                                                                                   value={network.relay.status}/>
        </div>
      </section>
      <section className="panel network-summary">
        <div className="card-kicker">RELAY</div>
        <h3>{network.relay.configured ? network.relay.status : "No organization relay configured"}</h3>
        <p>{network.relay.connected ? `${network.relay.activeConnections} active connections` : "The local runtime is not using a relay."}</p>
        <StatusBadge value={network.relay.status}/></section>
    </div>
    <section className="panel"><PanelHeading icon={Radio} title="Direct peers"/><NetworkPeerTable
        peers={network.peers}/></section>
    <section className="panel"><PanelHeading icon={GitBranch} title="Selected routes"/><RouteTable
        routes={network.routes}/></section>
    <section className="panel"><PanelHeading icon={ShieldCheck}
                                             title="Project membership"/><MembershipTable
        network={network}/></section>
    {client && <section className="panel onboarding-panel"><PanelHeading icon={Clipboard}
                                                                         title="Link onboarding"/><p
        className="panel-intro">Use the real Synesis onboarding commands. Invitation validation and
      admission remain backend-owned.</p>
      <div className="onboarding-grid">
        <div><label htmlFor="invite-peer">Invite a
          peer <span>optional expected node ID</span></label>
          <div className="inline-form"><input id="invite-peer" placeholder="Optional node ID"
                                              value={expectedPeer}
                                              onChange={(event) => setExpectedPeer(event.target.value)}/>
            <button className="button button-secondary" disabled={busy}
                    onClick={() => run(async () => {
                      const response = await client.invite(expectedPeer || undefined);
                      setInviteUri(response.inviteUri);
                      setAnswerOperation(response.operationId);
                    })}>Create invite
            </button>
          </div>
          {inviteUri && <><CopyField label="Invitation URI" value={inviteUri}/><CopyField
              label="Operation ID" value={answerOperation}/></>}</div>
        <div><label htmlFor="join-uri">Join with invitation URI</label>
          <div className="inline-form"><input id="join-uri" value={joinUri}
                                              placeholder="synesis://join/SLO1-…"
                                              onChange={(event) => setJoinUri(event.target.value)}/>
            <button className="button button-secondary" disabled={busy || !joinUri}
                    onClick={() => run(async () => {
                      const response = await client.join(joinUri);
                      setJoinResult(response);
                      setAnswerOperation(response.operationId);
                    })}>Join
            </button>
          </div>
          {joinResult && <><CopyField label="Answer URI" value={joinResult.answerUri}/>
            <button className="text-button onboarding-action" disabled={busy}
                    onClick={() => run(async () => {
                      await client.connect(joinResult.operationId);
                      setActionMessage("Join completed.");
                    })}>Complete join after the host answers <ChevronRight size={14}/></button>
          </>}</div>
      </div>
      <div className="answer-row"><label htmlFor="answer-uri">Host answer</label><input
          id="answer-uri" value={answerUri} placeholder="synesis://answer/SLA2-…"
          onChange={(event) => setAnswerUri(event.target.value)}/>
        <button className="button button-primary" disabled={busy || !answerOperation || !answerUri}
                onClick={() => run(async () => {
                  await client.answer(answerOperation, answerUri);
                  setActionMessage("Peer connected.");
                })}>Accept answer
        </button>
      </div>
      {actionMessage && <div className="action-message" role="status">{actionMessage}</div>}
    </section>}
  </div>;
}

function DiagnosticsView({diagnostics}: { diagnostics: Snapshot["diagnostics"] }) {
  return <div className="page-stack"><PageHeader eyebrow="HEALTH" title="Diagnostics"
                                                 description="Structured findings from the local Doctor service."/>
    <section className="diagnostic-overview panel">
      <div>
        <div className="card-kicker">OVERALL STATUS</div>
        <div className="diagnostic-status"><StatusBadge value={diagnostics.overallStatus}
                                                        large/><span>Report {diagnostics.reportId}</span>
        </div>
      </div>
      <div className="diagnostic-counts"><MiniStat label="Critical"
                                                   value={String(diagnostics.criticalCount)}/><MiniStat
          label="Errors" value={String(diagnostics.errorCount)}/><MiniStat label="Warnings"
                                                                           value={String(diagnostics.warningCount)}/><MiniStat
          label="Info" value={String(diagnostics.infoCount)}/></div>
    </section>
    {diagnostics.findings.length === 0 ? <EmptyState icon={ShieldCheck} title="No findings"
                                                     text="The Doctor service has not reported any findings for this project."/> :
        <section className="card-list">{diagnostics.findings.map((finding) => <FindingCard
            key={`${finding.code}-${finding.summary}`} finding={finding}/>)}</section>}</div>;
}

function ConnectionScreen({state, message}: { state: string; message: string }) {
  return <main className="connection-screen">
    <div className="connection-card">
      <div className="brand-mark large"><CircleDot size={22}/></div>
      <div className="eyebrow">SYNESIS LOCAL UI</div>
      <h1>{state === "CONNECTING" ? "Connecting to Synesis" : "The local control plane is unavailable"}</h1>
      <p>{message}</p>{state !== "CONNECTING" &&
        <div className="connection-help"><TerminalSquare size={16}/><span>Start the UI with <code>synesis ui</code>, then reload this page.</span>
        </div>}
      <div className="loading-line" aria-hidden="true"><span/></div>
    </div>
  </main>;
}

function PageHeader({eyebrow, title, description, action}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode
}) {
  return <div className="page-header">
    <div>
      <div className="eyebrow">{eyebrow}</div>
      <h1>{title}</h1><p>{description}</p></div>
    {action && <div>{action}</div>}</div>;
}

function PanelHeading({icon: Icon, title, action}: {
  icon: LucideIcon;
  title: string;
  action?: ReactNode
}) {
  return <div className="panel-heading">
    <div><Icon size={16}/><h2>{title}</h2></div>
    {action}</div>;
}

function MetricCard({label, value, detail, icon: Icon}: {
  label: string;
  value: number;
  detail: string;
  icon: LucideIcon
}) {
  return <div className="metric-card">
    <div className="metric-icon"><Icon size={17}/></div>
    <div className="metric-label">{label}</div>
    <div className="metric-value">{value}</div>
    <div className="metric-detail">{detail}</div>
  </div>;
}

function MiniStat({label, value}: { label: string; value: string }) {
  return <div className="mini-stat"><span>{label}</span><strong>{value}</strong></div>;
}

function StatusBadge({value, large = false}: { value: string; large?: boolean }) {
  const tone = statusTone(value);
  return <span className={`status-badge status-${tone} ${large ? "status-large" : ""}`}><span
      className="status-dot"/>{value.replaceAll("_", " ")}</span>;
}

function ConnectionBadge({state, message}: { state: string; message: string }) {
  return <span className={`connection-badge connection-${state.toLowerCase()}`}
               title={message}><span
      className="status-dot"/>{state === "LIVE" ? "Live" : state.toLowerCase()}</span>;
}

function EmptyState({icon: Icon, title, text}: { icon: LucideIcon; title: string; text: string }) {
  return <div className="empty-state panel">
    <div className="empty-icon"><Icon size={21}/></div>
    <h2>{title}</h2><p>{text}</p></div>;
}

function InlineEmpty({text}: { text: string }) {
  return <div className="inline-empty"><Check size={16}/> {text}</div>;
}

function FindingList({findings}: { findings: DiagnosticFinding[] }) {
  return <div className="finding-list">{findings.map((finding) => <div className="finding-row"
                                                                       key={`${finding.code}-${finding.summary}`}>
    <StatusBadge value={finding.severity}/>
    <div><strong>{finding.summary}</strong><span>{finding.code}</span></div>
  </div>)}</div>;
}

function FindingCard({finding}: { finding: DiagnosticFinding }) {
  return <article className="panel finding-card">
    <div className="finding-card-top"><StatusBadge value={finding.severity}/><span
        className="mono">{finding.code}</span></div>
    <h2>{finding.summary}</h2><p>{finding.explanation}</p>
    <div className="finding-recommendation">
      <strong>Recommendation</strong><span>{finding.recommendation}</span></div>
  </article>;
}

function NetworkPeerTable({peers}: { peers: NetworkSnapshot["peers"] }) {
  if (!peers.length) return <InlineEmpty text="No authenticated direct peers."/>;
  return <div className="table-wrap table-wrap-inner">
    <table>
      <thead>
      <tr>
        <th>Peer</th>
        <th>Health</th>
        <th>Liveness</th>
        <th>Established</th>
      </tr>
      </thead>
      <tbody>{peers.map((peer) => <tr key={peer.sessionId || peer.nodeId}>
        <td><strong>{shortId(peer.nodeId)}</strong><span
            className="mono table-secondary">{peer.nodeId}</span></td>
        <td><StatusBadge value={peer.health}/></td>
        <td>{peer.liveness}</td>
        <td>{formatDate(peer.establishedAt)}</td>
      </tr>)}</tbody>
    </table>
  </div>;
}

function RouteTable({routes}: { routes: NetworkSnapshot["routes"] }) {
  if (!routes.length) return <InlineEmpty text="No selected routes reported by the overlay."/>;
  return <div className="table-wrap table-wrap-inner">
    <table>
      <thead>
      <tr>
        <th>Destination</th>
        <th>Selected path</th>
        <th>Next hop</th>
        <th>Route</th>
      </tr>
      </thead>
      <tbody>{routes.map((route) => <tr key={`${route.destinationNodeId}-${route.kind}`}>
        <td className="mono">{shortId(route.destinationNodeId)}</td>
        <td className="mono">{route.path.length ? route.path.map(shortId).join(" → ") : "—"}</td>
        <td className="mono">{route.nextHop || "—"}</td>
        <td><StatusBadge value={route.kind}/></td>
      </tr>)}</tbody>
    </table>
  </div>;
}

function MembershipTable({network}: { network: NetworkSnapshot }) {
  if (!network.overlay.members.length) return <InlineEmpty
      text="No verified project membership is available."/>;
  return <div className="table-wrap table-wrap-inner">
    <table>
      <thead>
      <tr>
        <th>Member</th>
        <th>Status</th>
        <th>Direct edge</th>
      </tr>
      </thead>
      <tbody>{network.overlay.members.map((member) => {
        const direct = network.overlay.directEdges.some((edge) => edge.from === member.nodeId || edge.to === member.nodeId);
        return <tr key={member.nodeId}>
          <td className="mono">{shortId(member.nodeId)}</td>
          <td><StatusBadge value={member.status}/></td>
          <td>{direct ? "Observed" : "No direct edge"}</td>
        </tr>;
      })}</tbody>
    </table>
  </div>;
}

function CopyField({label, value}: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return <div className="copy-field"><span>{label}</span><code>{value}</code>
    <button className="icon-button" aria-label={`Copy ${label}`} onClick={() => {
      void navigator.clipboard?.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_500);
    }}>{copied ? <Check size={15}/> : <Clipboard size={15}/>}</button>
  </div>;
}

function statusTone(value: string): "good" | "warn" | "bad" | "neutral" {
  const normalized = value.toUpperCase();
  if (["HEALTHY", "RUNNING", "ACTIVE", "CONNECTED", "CURRENT", "DIRECT", "PEER_TRANSIT", "ORGANIZATION_RELAY", "AUTHORIZED", "PASS", "OK"].includes(normalized)) return "good";
  if (["DEGRADED", "WAITING", "PENDING", "UNCONFIGURED", "DISABLED", "UNKNOWN", "INFO", "WARNING"].includes(normalized)) return "warn";
  if (["ERROR", "FAILED", "CRITICAL", "UNREACHABLE", "REJECTED", "DENIED"].includes(normalized)) return "bad";
  return "neutral";
}

function networkHeadline(network: NetworkSnapshot): string {
  if (network.status === "UNCONFIGURED") return "Overlay authority is not configured.";
  if (network.status === "HEALTHY") return "Network state is healthy.";
  return `Network state is ${network.status.toLowerCase()}.`;
}

function networkDetail(network: NetworkSnapshot): string {
  if (network.overlay.status === "UNCONFIGURED") return "Physical Link state remains visible without claiming project membership.";
  return `${network.overlay.memberCount} verified members · ${network.routes.length} selected routes`;
}

function errorMessage(error: unknown): string {
  if (error instanceof ControlPlaneError) {
    if (error.code === "AUTH_REQUIRED" || error.code === "SESSION_EXPIRED") return "The local session expired. Restart with `synesis ui`.";
    return error.message;
  }
  return error instanceof Error ? error.message : "The local control plane could not be reached.";
}

function shortId(value: string): string {
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-5)}` : value;
}

function formatDate(value: string): string {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString([], {
    dateStyle: "medium",
    timeStyle: "short"
  });
}
