import {type ReactNode, useEffect, useRef, useState} from "react";
import type {LucideIcon} from "lucide-react";
import {
  Activity,
  ArrowLeft,
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  FolderOpen,
  GitBranch,
  Link2,
  Network,
  Plus,
  Search,
  ShieldCheck,
  Trash2,
  UserRound,
  Users,
  X,
} from "lucide-react";
import {
  type AgentSnapshot,
  consumeBootstrapToken,
  ControlPlaneClient,
  type DiagnosticFinding,
  type KnownProjectSnapshot,
  type NetworkPeerSnapshot,
  type NetworkSnapshot,
  type ProjectFolderChoice,
  type ProjectSelection,
  type Snapshot,
  clearSelectionId,
  selectionIdFromLocation,
} from "../api/controlPlane";
import {parseRoute, projectTabs, routePath, type ProjectView, type Route} from "./routes";

export type View = ProjectView | "projects";
type StatusTone = "good" | "warn" | "bad" | "neutral";
type CoordinationTab = "claims" | "tasks" | "capabilities" | "ownership";
type ProjectRegistryClient = Pick<ControlPlaneClient, "chooseProject" | "registerProject" | "removeProject">;

function asLiveRegistryProject(project: NonNullable<Snapshot["project"]>): KnownProjectSnapshot {
  return {
    ...project,
    firstObservedAt: project.createdAt,
    lastObservedAt: project.createdAt,
    status: "LIVE",
  };
}

function registryStateNote(status: KnownProjectSnapshot["status"]) {
  if (status === "INACTIVE") return "Runtime is not currently reachable.";
  if (status === "UNAVAILABLE") return "Remembered location unavailable.";
  if (status === "IDENTITY_MISMATCH") return "Project identity at this path does not match the stored identity.";
  return "";
}

function registryProjects(snapshot: Snapshot) {
  return snapshot.knownProjects?.length ? snapshot.knownProjects : snapshot.project ? [asLiveRegistryProject(snapshot.project)] : [];
}

function projectSelectionMessage(error: unknown): string {
  const code = error && typeof error === "object" && "code" in error && typeof error.code === "string"
    ? error.code : "";
  if (code === "SELECTION_EXPIRED") return "This invitation selection expired. Open the invitation again.";
  if (code === "SELECTION_CONSUMED") return "This invitation was already routed.";
  if (code === "PROJECT_UNAVAILABLE") return "That project is no longer available. Choose another project.";
  return error instanceof Error ? error.message : "Project selection is unavailable.";
}

function projectRegistryMessage(error: unknown): string {
  const code = error && typeof error === "object" && "code" in error && typeof error.code === "string"
    ? error.code : "";
  if (code === "NOT_FOUND") return "That folder could not be found.";
  if (code === "IDENTITY_MISMATCH") return "That project identity is already registered at another path.";
  if (code === "CURRENT_PROJECT_PROTECTED") return "The project serving this page cannot be removed from its own registry.";
  if (code === "PROJECT_NOT_FOUND") return "That project is no longer in the registry.";
  if (code === "REGISTRY_FULL") return "The installation project registry is full.";
  if (code === "GIT_INIT_FAILED") return "Git could not be initialized in that folder.";
  if (code === "PROJECT_INITIALIZATION_FAILED") return "Synesis could not initialize that folder.";
  return error instanceof Error ? error.message : "Project registry update failed.";
}

export function App() {
  const [route, setRoute] = useState<Route>(() => parseRoute());
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [connection, setConnection] = useState<"CONNECTING" | "LIVE" | "DEGRADED" | "OFFLINE">("CONNECTING");
  const [message, setMessage] = useState("Connecting to the local control plane");
  const [selection, setSelection] = useState<ProjectSelection | null>(null);
  const [selectionError, setSelectionError] = useState<Error | null>(null);
  const clientRef = useRef<ControlPlaneClient | null>(null);
  const [mockRequested] = useState(() => import.meta.env.DEV && typeof window !== "undefined" && new URLSearchParams(window.location.search).get("mock") === "1");
  const mockMode = import.meta.env.DEV && mockRequested;
  const registryClient: ProjectRegistryClient | null = mockMode ? {
    chooseProject: async (): Promise<ProjectFolderChoice> => ({
      state: "SELECTED",
      path: "C:\\Projects\\New project",
      name: "New project",
    }),
    registerProject: async (path: string): Promise<Record<string, unknown>> => {
      setSnapshot((current) => {
        if (!current || current.knownProjects.some((project) => project.path === path)) return current;
        const observedAt = new Date().toISOString();
        return {
          ...current,
          knownProjects: [...current.knownProjects, {
            id: "proj_preview",
            name: path.split(/[\\/]/).filter(Boolean).at(-1) ?? "New project",
            path,
            createdAt: observedAt,
            firstObservedAt: observedAt,
            lastObservedAt: observedAt,
            status: "INACTIVE",
          }],
        };
      });
      return {state: "REGISTERED"};
    },
    removeProject: async (projectId: string): Promise<Record<string, unknown>> => {
      setSnapshot((current) => current ? {
        ...current,
        knownProjects: current.knownProjects.filter((project) => project.id !== projectId),
      } : current);
      return {state: "REMOVED"};
    },
  } : clientRef.current;

  useEffect(() => {
    const handlePopState = () => setRoute(parseRoute());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (mockMode) {
      let disposed = false;
      void import("../dev/mockSnapshot").then(({mockSnapshot}) => {
        if (disposed) return;
        setSnapshot(mockSnapshot);
        setConnection("LIVE");
        setMessage("Mock local state");
      });
      return () => {
        disposed = true;
      };
    }

    const client = new ControlPlaneClient();
    clientRef.current = client;
    let stopEvents: (() => void) | undefined;
    let disposed = false;

    const start = async () => {
      try {
        const bootstrap = consumeBootstrapToken();
        if (!bootstrap) {
          if (typeof window !== "undefined" && window.location.pathname === "/") {
            window.history.replaceState(null, "", "/projects");
          }
          throw new Error("Open Synesis with the supported synesis ui command.");
        }
        const pendingSelectionId = selectionIdFromLocation();
        if (typeof window !== "undefined" && window.location.pathname === "/") {
          const suffix = pendingSelectionId ? `#selectionId=${encodeURIComponent(pendingSelectionId)}` : "";
          window.history.replaceState(null, "", `/projects${suffix}`);
        }
        const initial = await client.startSession(bootstrap);
        if (disposed) return;
        setSnapshot(initial);
        if (pendingSelectionId) {
          try {
            setSelection(await client.projectSelection(pendingSelectionId));
          } catch (error) {
            setSelectionError(new Error(projectSelectionMessage(error)));
          }
        }
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
              setMessage("Resynchronizing authoritative state");
            } else if (event.type === "link.updated") {
              void client.snapshot().then((next) => {
                if (disposed) return;
                setSnapshot(next);
                setConnection("LIVE");
                setMessage("Live local state");
              }).catch(() => {
                // The event stream remains authoritative if a refresh races shutdown.
              });
            }
          },
          onError: (error) => {
            setConnection("DEGRADED");
            setMessage(error.message);
          },
        });
      } catch (error) {
        if (disposed) return;
        setConnection("OFFLINE");
        setMessage(error instanceof Error ? error.message : "Control plane unavailable");
      }
    };

    void start();
    return () => {
      disposed = true;
      stopEvents?.();
    };
  }, [mockMode]);

  if (!snapshot) return <ConnectionScreen state={connection} message={message}/>;

  const navigate = (next: Route) => {
    const path = routePath(next);
    const browserPath = mockMode ? path + "?mock=1" : path;
    if (typeof window !== "undefined" && window.location.pathname !== path) {
      window.history.pushState(null, "", browserPath);
    }
    setRoute(parseRoute(path));
  };
  const currentProject = snapshot.project?.id === (route.kind === "project" ? route.projectId : "") ? snapshot.project : null;
  const registryProject = route.kind === "project"
    ? (snapshot.knownProjects ?? []).find((project) => project.id === route.projectId) ?? (currentProject ? asLiveRegistryProject(currentProject) : null)
    : null;
  const liveProjectAvailable = route.kind === "project"
    && currentProject !== null
    && (registryProject === null || registryProject.status === "LIVE");

  return (
    <div className={cx("app-shell", route.kind === "projects" && "projects-page")}>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <header className="site-header">
        <div className="site-header-left">
          <button className="brand-button" type="button" onClick={() => navigate({kind: "projects"})} aria-label="Open Projects registry">
            <img className="brand-logo" src="/synesis-logo.png" alt="" aria-hidden="true" />
            <span className="brand-name">Synesis</span>
          </button>
          <span className="site-header-spacer" aria-hidden="true"/>
        </div>
      </header>

      <main id="main-content" className="main-content">
        {route.kind === "projects" ? (
          <div className="page-frame registry-frame">
            <ProjectsView
              snapshot={snapshot}
              client={registryClient}
              onSnapshotRefresh={async () => {
                if (clientRef.current) setSnapshot(await clientRef.current.snapshot());
              }}
              onOpenProject={(project) => navigate({kind: "project", projectId: project.id, view: "overview"})}
              onOpenNetwork={snapshot.project ? () => navigate({kind: "project", projectId: snapshot.project!.id, view: "network"}) : undefined}
            />
          </div>
        ) : !registryProject ? (
          <div className="page-frame">
            <RegistryProjectView project={null} onBack={() => navigate({kind: "projects"})}/>
          </div>
        ) : !liveProjectAvailable ? (
          <div className="page-frame">
            <RegistryProjectView project={registryProject} onBack={() => navigate({kind: "projects"})}/>
          </div>
        ) : (
          <div className="page-frame">
            <div className="project-resource-header">
            <ProjectHeader snapshot={snapshot} title={snapshot.project?.name ?? "Project"} onBack={() => navigate({kind: "projects"})}/>
            <ProjectTabs view={route.view} onNavigate={(view) => navigate({kind: "project", projectId: snapshot.project!.id, view})}/>
            </div>
            {route.view === "overview" && <ProjectOverviewView snapshot={snapshot} onNavigate={(view) => navigate({kind: "project", projectId: snapshot.project!.id, view})}/>}
            {route.view === "agents" && <AgentsView agents={snapshot.agents}/>}
            {route.view === "coordination" && <CoordinationView snapshot={snapshot}/>}
            {route.view === "network" && <NetworkView network={snapshot.network} onboarding={snapshot.onboarding} client={clientRef.current}/>}
            {route.view === "diagnostics" && <DiagnosticsView diagnostics={snapshot.diagnostics}/>}
          </div>
        )}
      </main>
      {selection && <ProjectSelectionView
        selection={selection}
        client={clientRef.current}
        onCompleted={() => { clearSelectionId(); setSelection(null); }}
        onError={(error) => setSelectionError(error)}
      />}
      {selectionError && !selection && <div className="selection-toast" role="alert">{selectionError.message}</div>}
    </div>
  );
}

export function ProjectSelectionView({selection, client, onCompleted, onError}: {
  selection: ProjectSelection;
  client: ControlPlaneClient | null;
  onCompleted(): void;
  onError(error: Error): void;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const choose = async (projectId: string) => {
    if (!client || busy) return;
    setBusy(true);
    setMessage("");
    try {
      await client.selectProject(selection.selectionId, projectId);
      onCompleted();
    } catch (error) {
      const failure = error instanceof Error ? error : new Error("Project selection failed.");
      setMessage(projectSelectionMessage(error));
      onError(failure);
    } finally {
      setBusy(false);
    }
  };

  return <div className="selection-backdrop" role="presentation">
    <section className="selection-dialog panel" role="dialog" aria-modal="true" aria-labelledby="selection-title">
      <p className="eyebrow">Local invitation routing</p>
      <h1 id="selection-title">Choose project for invitation</h1>
      <p className="muted">Select the local project that should receive this invitation.</p>
      <div className="selection-list" role="list" aria-label="Eligible projects">
        {selection.projects.map((project) => <button
          className="selection-project"
          key={project.projectId}
          type="button"
          disabled={busy}
          onClick={() => void choose(project.projectId)}
        >
          <span>{project.displayName}</span>
          <span className="mono selection-project-id">{project.projectId}</span>
        </button>)}
      </div>
      {message && <p className="selection-error" role="alert">{message}</p>}
    </section>
  </div>;
}

export function ProjectsView({snapshot, client, onSnapshotRefresh, onOpenProject, onOpenNetwork}: {snapshot: Snapshot; client?: ProjectRegistryClient | null; onSnapshotRefresh?: () => Promise<void>; onOpenProject?: (project: KnownProjectSnapshot) => void; onOpenNetwork?: () => void}) {
  const projects = registryProjects(snapshot);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<KnownProjectSnapshot["status"] | "ALL">("ALL");
  const [addOpen, setAddOpen] = useState(false);
  const [addPath, setAddPath] = useState("");
  const [addProjectName, setAddProjectName] = useState("");
  const [removeTarget, setRemoveTarget] = useState<KnownProjectSnapshot | null>(null);
  const [pendingRemovalIds, setPendingRemovalIds] = useState<string[]>([]);
  const [mutationError, setMutationError] = useState("");
  const [removalError, setRemovalError] = useState("");
  const [mutationBusy, setMutationBusy] = useState(false);
  useEffect(() => {
    if (!removalError) return;
    const timeout = window.setTimeout(() => setRemovalError(""), 5000);
    return () => window.clearTimeout(timeout);
  }, [removalError]);
  const visibleProjects = projects.filter((project) => !pendingRemovalIds.includes(project.id));
  const liveCount = visibleProjects.filter((project) => project.status === "LIVE").length;
  const connectionCount = snapshot.network.peers.length;
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const filteredProjects = visibleProjects.filter((project) => {
    const matchesQuery = !normalizedQuery
      || [project.name, project.id, project.path, project.status]
        .some((value) => value.toLocaleLowerCase().includes(normalizedQuery));
    return matchesQuery && (statusFilter === "ALL" || project.status === statusFilter);
  });

  const refreshRegistry = async () => {
    await onSnapshotRefresh?.();
  };

  const addProject = async () => {
    if (!client || !addPath || mutationBusy) return;
    setMutationBusy(true);
    setMutationError("");
    try {
      await client.registerProject(addPath);
      await refreshRegistry();
      setAddPath("");
      setAddOpen(false);
    } catch (error) {
      setMutationError(projectRegistryMessage(error));
    } finally {
      setMutationBusy(false);
    }
  };

  const chooseProjectFolder = async () => {
    if (!client || mutationBusy) return;
    setMutationBusy(true);
    setMutationError("");
    try {
      const choice = await client.chooseProject();
      if (choice.state === "SELECTED") {
        setAddPath(choice.path);
        setAddProjectName(choice.name);
      }
    } catch (error) {
      setMutationError(projectRegistryMessage(error));
    } finally {
      setMutationBusy(false);
    }
  };

  const removeProject = async () => {
    if (!client || !removeTarget || mutationBusy) return;
    const target = removeTarget;
    setRemoveTarget(null);
    setMutationError("");
    setPendingRemovalIds((current) => current.includes(target.id) ? current : [...current, target.id]);
    void (async () => {
      try {
        await client.removeProject(target.id);
        await refreshRegistry();
        setPendingRemovalIds((current) => current.filter((id) => id !== target.id));
      } catch (error) {
        setPendingRemovalIds((current) => current.filter((id) => id !== target.id));
        setRemovalError(projectRegistryMessage(error));
      }
    })();
  };

  return (
    <div className="registry-page-layout">
      <section className="registry-hero panel" aria-labelledby="registry-title">
        <div className="registry-hero-copy">
          <p className="registry-kicker"><Activity size={14} aria-hidden="true"/>Local workspace registry</p>
          <h1 id="registry-title">Projects</h1>
          <p>Open a workspace, inspect its runtime, or bring another local folder into Synesis.</p>
        </div>
        {client && <button className="button button-primary registry-add-button" type="button" onClick={() => { setMutationError(""); setRemovalError(""); setAddPath(""); setAddProjectName(""); setAddOpen(true); }}><Plus size={17} aria-hidden="true"/>Add project</button>}
        <div className="registry-overview" aria-label={projects.length + " known projects, " + liveCount + " live"}>
          <div className="registry-overview-item">
            <span>Known projects</span>
            <strong>{projects.length}</strong>
          </div>
          <div className="registry-overview-item">
            <span>Live runtimes</span>
            <strong>{liveCount}</strong>
          </div>
          <div className="registry-overview-item">
            <span>Peer connections</span>
            <strong>{connectionCount}</strong>
          </div>
        </div>
      </section>

      <section className="registry-section" aria-labelledby="registry-list-title">
        <div className="registry-heading">
          <div className="registry-heading-copy">
            <p className="registry-kicker">Workspace registry</p>
            <h2 id="registry-list-title">Choose where to work</h2>
          </div>
          <div className="registry-heading-actions">
            <label className="project-search">
              <span className="registry-control-label">Search</span>
              <span className="project-search-field"><Search size={16} aria-hidden="true"/><input aria-label="Search projects" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Name, path, or identity" type="search" /></span>
            </label>
            <label className="project-status-filter">
              <span className="registry-control-label">Status</span>
              <select aria-label="Filter projects by status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as typeof statusFilter)}>
                <option value="ALL">All statuses</option>
                <option value="LIVE">Live</option>
                <option value="INACTIVE">Inactive</option>
                <option value="UNAVAILABLE">Unavailable</option>
                <option value="IDENTITY_MISMATCH">Identity mismatch</option>
              </select>
            </label>
          </div>
        </div>
        {projects.length > 0 && <p className="registry-results" aria-live="polite">Showing <strong>{filteredProjects.length}</strong> of <strong>{visibleProjects.length}</strong> {visibleProjects.length === 1 ? "project" : "projects"}</p>}
        {visibleProjects.length === 0 && pendingRemovalIds.length > 0 ? (
          <EmptyState icon={Trash2} title="Removal in progress" detail="The project is hidden while the registry confirms the removal."/>
        ) : projects.length === 0 ? (
          <EmptyState icon={FolderOpen} title="No projects yet" detail="Projects appear after legitimate Synesis discovery through init, runtime startup, or MCP attachment."/>
        ) : filteredProjects.length === 0 ? (
          <EmptyState icon={Search} title="No matching projects" detail="Try a different project name, identity, path, or status."/>
        ) : (
          <div className="project-list panel" role="list" aria-label="Known projects">
            {filteredProjects.map((project) => (
              <div className="project-row" key={project.id + project.path} role="listitem">
                <div className="project-row-heading">
                  <span className="project-folder-mark" aria-hidden="true"><FolderOpen size={18}/></span>
                  <div className="project-row-main">
                    {onOpenProject ? <button className="registry-project-link" type="button" onClick={() => onOpenProject(project)}>{project.name}</button> : <strong className="registry-project-name">{project.name}</strong>}
                    <span className="mono project-path" title={project.path}>{project.path}</span>
                  </div>
                  <div className="project-row-state"><StatusBadge value={project.status}/></div>
                </div>
                <div className="project-row-identity">
                  <span>Project identity</span>
                  <span className="mono">{project.id}</span>
                </div>
                {project.status !== "LIVE" && <p className="project-row-note">{registryStateNote(project.status)}</p>}
                <div className="project-row-action">
                  <div className="project-row-actions">
                    {onOpenProject && project.status === "LIVE" && project.id === snapshot.project?.id
                      ? <button className="text-action" type="button" onClick={() => onOpenProject(project)}>Open project <ChevronRight size={14}/></button>
                      : onOpenProject && project.status !== "LIVE"
                        ? <button className="text-action" type="button" onClick={() => onOpenProject(project)}>View details <ChevronRight size={14}/></button>
                        : <span className="muted">{project.status === "LIVE" ? "Runtime not attached" : "Registry detail"}</span>}
                    {client && project.id !== snapshot.project?.id && <button className="button button-secondary project-remove-button" type="button" aria-label={`Remove ${project.name} from registry`} onClick={() => { setMutationError(""); setRemovalError(""); setRemoveTarget(project); }}><Trash2 size={15} aria-hidden="true"/><span>Remove</span></button>}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
      <ConnectionsRail snapshot={snapshot} onOpenNetwork={onOpenNetwork}/>
      {addOpen && <DetailModal title="Add project to registry" onClose={() => { if (!mutationBusy) setAddOpen(false); }}>
        <form className="registry-form" onSubmit={(event) => { event.preventDefault(); void addProject(); }}>
          <p className="muted">Choose a folder to add. Existing state is preserved.</p>
          {mutationError && <div className="registry-folder-error" role="alert"><p>{mutationError}</p><button className="icon-button registry-folder-error-close" type="button" aria-label="Dismiss project folder error" onClick={() => setMutationError("")}><X size={15} aria-hidden="true"/></button></div>}
          <button className="registry-folder-picker" type="button" aria-label={addProjectName ? `Selected project folder ${addProjectName}` : "Choose project folder"} onClick={() => void chooseProjectFolder()} disabled={mutationBusy}>
            <FolderOpen size={20} aria-hidden="true"/>
            <span className="registry-folder-picker-copy">
              <strong>{addProjectName || "Choose project folder"}</strong>
              <span>{addPath || "Open Windows Explorer to select a folder"}</span>
            </span>
            <ChevronRight size={17} aria-hidden="true"/>
          </button>
          <div className="registry-form-actions"><button className="button button-secondary" type="button" onClick={() => setAddOpen(false)} disabled={mutationBusy}>Cancel</button><button className="button button-primary" type="submit" disabled={!addPath || mutationBusy}>{mutationBusy ? "Adding…" : "Add project"}</button></div>
        </form>
      </DetailModal>}
      {removeTarget && <DetailModal title="Remove project from registry" onClose={() => { if (!mutationBusy) setRemoveTarget(null); }}>
        <div className="registry-form">
          <p>Remove <strong>{removeTarget.name}</strong> from the local project registry?</p>
          <p className="muted">Only the registry entry is removed. The project folder, `.synesis` state, and Link identity stay untouched.</p>
          <div className="registry-form-actions"><button className="button button-secondary" type="button" onClick={() => setRemoveTarget(null)} disabled={mutationBusy}>Cancel</button><button className="button button-danger" type="button" onClick={() => void removeProject()} disabled={mutationBusy}>{mutationBusy ? "Removing…" : "Remove from registry"}</button></div>
        </div>
      </DetailModal>}
      {removalError && <div className="selection-toast registry-operation-toast" role="alert">{removalError}</div>}
    </div>
  );
}

function ConnectionsRail({snapshot, onOpenNetwork}: {snapshot: Snapshot; onOpenNetwork?: () => void}) {
  const peers = snapshot.network.peers;
  const [terminationTarget, setTerminationTarget] = useState<{peer: NetworkPeerSnapshot; routeKind: string} | null>(null);
  const [connectionTarget, setConnectionTarget] = useState<{peer: NetworkPeerSnapshot; route: NetworkSnapshot["routes"][number] | null; memberStatus: string} | null>(null);
  return (
    <aside className="connections-rail" aria-label="Connections">
      <section className="connections-panel panel">
        <div className="rail-section-heading">
          <div className="rail-heading-copy"><span className="rail-heading-icon" aria-hidden="true"><Network size={17}/></span><div><p>Runtime network</p><h2>Connections</h2></div></div>
          <span className="rail-count">{peers.length}</span>
        </div>
        <p className="rail-caption">Current runtime connections</p>
        {peers.length === 0 ? <div className="rail-empty"><span className="rail-empty-icon" aria-hidden="true"><Network size={20}/></span><h3>No active peers</h3><p className="rail-empty-copy">No peer connections are currently projected.</p>{onOpenNetwork && <button className="text-action" type="button" onClick={onOpenNetwork}>Open network <ChevronRight size={14}/></button>}</div> : (
          <div className="connection-list">
            {peers.map((peer) => {
              const route = snapshot.network.routes.find((item) => item.destinationNodeId === peer.nodeId);
              const connected = peer.usable && peer.authenticated;
              return (
                <div className="connection-row" key={peer.nodeId + peer.sessionId}>
                  <button className="connection-detail-button" type="button" aria-label={`View connection details for ${peer.nodeId}`} onClick={() => setConnectionTarget({peer, route: route ?? null, memberStatus: snapshot.network.overlay.members.find((item) => item.nodeId === peer.nodeId)?.status ?? "UNKNOWN"})}>
                  <span className="connection-peer">
                    <strong>{peer.nodeId}</strong>
                    <span className="mono">{route?.kind ?? "No route projected"}</span>
                  </span>
                  <span className={cx("connection-status", connected ? "is-connected" : "is-unreachable")}><span className="status-dot"/>{peer.liveness || (connected ? "CONNECTED" : "UNREACHABLE")}</span>
                  </button>
                  <div className="connection-actions">
                    {onOpenNetwork && <button className="text-action" type="button" onClick={(event) => { event.stopPropagation(); onOpenNetwork(); }}>View network <ChevronRight size={14}/></button>}
                    <button className="icon-button connection-delete-button" type="button" aria-label={`Terminate connection to ${peer.nodeId}`} title="Terminate connection" onClick={(event) => { event.stopPropagation(); setTerminationTarget({peer, routeKind: route?.kind ?? "UNKNOWN"}); }}><Trash2 size={15}/></button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>
      {connectionTarget && <ConnectionDetailModal peer={connectionTarget.peer} route={connectionTarget.route} memberStatus={connectionTarget.memberStatus} onClose={() => setConnectionTarget(null)}/>}
      {terminationTarget && <ConnectionTerminationModal peer={terminationTarget.peer} routeKind={terminationTarget.routeKind} onClose={() => setTerminationTarget(null)}/>}
    </aside>
  );
}

export function ProjectHeader({snapshot, title, onBack}: {snapshot: Snapshot; title: string; onBack(): void}) {
  const project = snapshot.project;
  return (
    <section className="project-header project-overview-header" aria-label="Project identity">
      <button className="project-back-link" type="button" aria-label="Back to projects" onClick={onBack}><ArrowLeft size={15} aria-hidden="true"/>Projects</button>
      <div className="project-overview-grid">
        <div className="project-header-title">
          <h1>{title}</h1>
          <StatusBadge value={project ? snapshot.runtime.status : "UNAVAILABLE"}/>
        </div>
        <div className="project-overview-meta">
          <div className="project-overview-meta-item">
            <Copy size={16} aria-hidden="true"/>
            <span className="project-overview-meta-label">Project ID</span>
            <strong className="mono" title={project?.id}>{project?.id ?? "—"}</strong>
          </div>
          <div className="project-overview-meta-item">
            <FolderOpen size={16} aria-hidden="true"/>
            <span className="project-overview-meta-label">Local path</span>
            <strong className="mono" title={project?.path}>{project?.path ?? "—"}</strong>
          </div>
        </div>
      </div>
    </section>
  );
}

export function RegistryProjectView({project, onBack}: {project: KnownProjectSnapshot | null; onBack(): void}) {
  const title = project ? project.name : "Project not found";
  const status = project?.status ?? "UNAVAILABLE";
  const detail = status === "INACTIVE"
    ? "This project is known to Synesis, but no local runtime is currently available. Live coordination state is unavailable until that project runtime is running."
    : status === "UNAVAILABLE"
      ? "The remembered project path is not currently available. Synesis has not searched for or recreated the project."
      : status === "IDENTITY_MISMATCH"
        ? "The project at the remembered path does not match the stored project identity. Synesis will not treat it as the remembered project."
        : status === "LIVE"
          ? "Live state for this project is not available in the current control-plane session."
          : "The selected project is not present in the current known-project registry.";
  return (
    <section className="registry-detail-stack">
      <nav className="breadcrumb-bar" aria-label="Breadcrumb">
        <button className="breadcrumb-link" type="button" onClick={onBack}>Projects</button>
        <ChevronRight size={14} aria-hidden="true"/>
        <strong aria-current="page">{title}</strong>
      </nav>
      <section className="registry-project panel">
        <div className="registry-project-heading">
          <div className="registry-project-title">
            <button className="button button-secondary project-header-back registry-project-back" type="button" onClick={onBack}>
              <ArrowLeft size={16} aria-hidden="true"/>
              <span className="sr-only">Back to projects</span>
            </button>
            <div>
            <p className="eyebrow">Known project</p>
            <h1>{title}</h1>
            </div>
          </div>
          <StatusBadge value={status}/>
        </div>
        {project && (
          <div className="registry-project-meta">
            <DetailRow label="Project Identity" value={<span className="mono">{project.id}</span>}/>
            <DetailRow label="Local Path" value={<span className="mono">{project.path}</span>}/>
          </div>
        )}
        <div className="registry-project-message">
          <h2>{status === "IDENTITY_MISMATCH" ? "Identity mismatch" : status === "UNAVAILABLE" ? "Project location unavailable" : status === "INACTIVE" ? "Runtime unavailable" : "Live state unavailable"}</h2>
          <p>{detail}</p>
        </div>
      </section>
    </section>
  );
}

function ProjectTabs({view, onNavigate}: {view: Exclude<View, "projects">; onNavigate(view: Exclude<View, "projects">): void}) {
  return (
    <nav className="project-tabs" aria-label="Project views">
      {projectTabs.map((tab) => (
        <button key={tab.id} className={cx("project-tab", view === tab.id && "is-active")} type="button" onClick={() => onNavigate(tab.id)} aria-current={view === tab.id ? "page" : undefined}>{tab.label}</button>
      ))}
    </nav>
  );
}

function ProjectOverviewView({snapshot, onNavigate}: {snapshot: Snapshot; onNavigate(view: Exclude<View, "projects">): void}) {
  const authenticatedPeers = snapshot.network.peers.filter((peer) => peer.authenticated).length;
  return (
    <div className="view-stack">
      <section className="project-status-panel panel" aria-labelledby="project-status-title">
        <div className="project-status-heading">
          <div className="project-status-icon"><Activity size={21} aria-hidden="true"/></div>
          <div>
            <h2 id="project-status-title">Project status</h2>
            <p>Key components and health at a glance.</p>
          </div>
        </div>
        <div className="project-status-metrics">
          <OverviewMetric icon={Activity} label="Providers">
            {snapshot.providers.length === 0 ? <span className="muted">No provider records</span> : <div className="metric-provider-list">{snapshot.providers.map((provider) => <div className="metric-provider" key={provider.id}><span>{provider.id}</span><StatusBadge value={provider.status}/></div>)}</div>}
          </OverviewMetric>
          <OverviewMetric icon={Users} label="Participants" value={snapshot.runtime.activeAgentCount} detail="Active agents in the current runtime"/>
          <OverviewMetric icon={GitBranch} label="Active WorkGroups" value={snapshot.workgroups.length} detail="WorkGroups in this project"/>
          <OverviewMetric icon={Network} label="Authenticated Peers" value={authenticatedPeers} detail="Peers with authenticated access"/>
          <OverviewMetric icon={ShieldCheck} label="Doctor" value={<StatusBadge value={snapshot.diagnostics.overallStatus || "UNKNOWN"}/>} detail="Current diagnostic projection"/>
        </div>
      </section>

      <div className="overview-grid">
        <OverviewSection title="Coordination" icon={GitBranch} action={<TextAction onClick={() => onNavigate("coordination")}>Open coordination</TextAction>}>
          <div className="overview-two-column">
            <div className="overview-empty-block">
              <div className="overview-panel-icon"><Users size={20} aria-hidden="true"/></div>
              {snapshot.workgroups.length === 0 ? <><h3>No workgroups in this project</h3><p className="muted">Create workgroups to enable distributed agents, tasks, and shared capabilities.</p></> : <><h3>WorkGroups</h3><div className="overview-list">{snapshot.workgroups.slice(0, 4).map((group) => <div className="overview-list-row" key={group.id}><span><Check size={14}/>{group.id}</span><StatusBadge value={group.status}/></div>)}</div></>}
            </div>
            <div>
              <h3>Projection</h3>
              <div className="overview-stat-list">
                <OverviewStat label="Claims" value={snapshot.claims.length}/>
                <OverviewStat label="Tasks" value={snapshot.tasks.length}/>
                <OverviewStat label="Capabilities" value={snapshot.capabilities.length}/>
              </div>
            </div>
          </div>
        </OverviewSection>
        <OverviewSection title="Network" icon={Network} action={<TextAction onClick={() => onNavigate("network")}>Open network</TextAction>}>
          <div className="overview-two-column">
            <div>
              <h3>Connection boundary</h3>
              <div className="boundary-list">
                <BoundaryLine label="Physical peers" value={snapshot.network.peers.length} tone="physical"/>
                <BoundaryLine label="Signed membership" value={snapshot.network.overlay.memberCount} tone="membership"/>
                <BoundaryLine label="Server-selected routes" value={snapshot.network.routes.length} tone="routes"/>
              </div>
            </div>
            <div className="network-overview-copy">
              <StatusBadge value={snapshot.network.status}/>
              <p>{networkDetail(snapshot.network)}</p>
            </div>
          </div>
        </OverviewSection>
      </div>
    </div>
  );
}

function OverviewMetric({icon: Icon, label, value, detail, children}: {icon: LucideIcon; label: string; value?: ReactNode; detail?: string; children?: ReactNode}) {
  return (
    <div className="project-status-metric">
      <div className="metric-icon"><Icon size={20} aria-hidden="true"/></div>
      <div className="metric-content">
        <span className="metric-label">{label}</span>
        {children ?? <strong className="metric-value">{value}</strong>}
        {detail && <span className="metric-detail">{detail}</span>}
      </div>
    </div>
  );
}

export function AgentsView({agents}: {agents: AgentSnapshot[]}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = selectedId ? agents.find((agent) => agent.id === selectedId) ?? null : null;
  useEffect(() => {
    if (selectedId && !agents.some((agent) => agent.id === selectedId)) setSelectedId(null);
  }, [agents, selectedId]);

  return (
    <div className="view-stack">
      <div className="view-intro">
        <div><p className="eyebrow">Project participants</p><h2>Agents</h2><p>Verified participant state projected by the local control plane.</p></div>
        <div className="view-intro-meta"><span>{agents.length} participants</span><button className="icon-button" type="button" aria-label="Filter agents"><Search size={17}/></button></div>
      </div>
      {agents.length === 0 ? <EmptyState icon={Users} title="No agents yet" detail="Agent state will appear after a provider session is attached to this project."/> : (
        <section className="data-table-wrap panel table-panel" aria-label="Agents table">
          <table className="data-table agents-table">
            <thead><tr><th>Participant Identity</th><th>Provider</th><th>Goal</th><th>Coordination State</th><th>Current Work</th><th>Claims</th><th>Last Verified Activity</th></tr></thead>
            <tbody>{agents.map((agent) => (
              <tr key={agent.id} className={cx(selected?.id === agent.id && "is-selected")} tabIndex={0} aria-selected={selected?.id === agent.id} onClick={() => setSelectedId(agent.id)} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedId(agent.id); } }}>
                <td><strong>{agent.id}</strong></td>
                <td><span className="provider-token provider-token-compact"><span className="provider-mark">{agent.provider.slice(0, 2).toUpperCase()}</span>{agent.provider}</span></td>
                <td className="table-cell-wrap">{agent.goal || "—"}</td>
                <td><StatusBadge value={agent.state}/></td>
                <td className="table-cell-wrap">{agent.currentWork?.goal || "Idle"}</td>
                <td>{agent.claims.length}</td>
                <td><span className="activity-cell"><span>{formatDate(agent.lastVerifiedActivity)}</span><Activity size={14} aria-label="Verified activity"/></span></td>
              </tr>
            ))}</tbody>
          </table>
        </section>
      )}
      {selected && <AgentInspector agent={selected} onClose={() => setSelectedId(null)}/>}
    </div>
  );
}

function AgentInspector({agent, onClose}: {agent: AgentSnapshot; onClose(): void}) {
  return (
    <DetailModal title="Agent Detail" onClose={onClose}>
      <div className="inspector-title"><span className="eyebrow">Participant</span><h2>{agent.id}</h2><StatusBadge value={agent.state}/></div>
      <DetailList>
        <DetailRow label="Provider" value={agent.provider}/>
        <DetailRow label="Goal" value={agent.goal || "—"}/>
        <DetailRow label="Current Work" value={agent.currentWork?.goal || "Idle"}/>
        <DetailRow label="Role" value={agent.currentWork?.role || "—"}/>
        <DetailRow label="WorkGroup" value={agent.currentWork?.workGroupId || "—"}/>
        <DetailRow label="Last Verified" value={formatDate(agent.lastVerifiedActivity)}/>
      </DetailList>
      <InspectorSection title="Claims">
        {agent.claims.length === 0 ? <p className="muted">No claims held.</p> : <ValueList values={agent.claims.map((claim) => selectorText(claim))}/>}
      </InspectorSection>
      <InspectorSection title="Dependency Waits">
        {agent.waitingOn.length === 0 ? <p className="muted">No dependency waits.</p> : <ValueList values={agent.waitingOn}/>}
      </InspectorSection>
    </DetailModal>
  );
}

function CoordinationView({snapshot}: {snapshot: Snapshot}) {
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(snapshot.workgroups[0]?.id ?? null);
  const [tab, setTab] = useState<CoordinationTab>("claims");
  const [detailOpen, setDetailOpen] = useState(false);
  const group = snapshot.workgroups.find((item) => item.id === selectedGroupId) ?? snapshot.workgroups[0] ?? null;
  useEffect(() => {
    if (selectedGroupId && !snapshot.workgroups.some((item) => item.id === selectedGroupId)) setSelectedGroupId(snapshot.workgroups[0]?.id ?? null);
  }, [selectedGroupId, snapshot.workgroups]);

  return (
    <div className="view-stack">
      <div className="coordination-layout">
        <aside className="workgroup-rail panel" aria-label="WorkGroups">
          <div className="rail-heading"><span>WorkGroups</span><span className="count-badge">{snapshot.workgroups.length}</span></div>
          <div className="workgroup-list">
            {snapshot.workgroups.length === 0 ? <p className="muted rail-empty">No workgroups.</p> : snapshot.workgroups.map((item) => (
              <button key={item.id} type="button" className={cx("workgroup-item", group?.id === item.id && "is-selected")} onClick={() => setSelectedGroupId(item.id)}>
                <strong>{item.id}</strong><span><StatusBadge value={item.status}/><span>{item.participants.length} participants</span></span>
              </button>
            ))}
          </div>
        </aside>
        <section className="coordination-main">
          <div className="coordination-header panel">
            <div><p className="eyebrow">Coordination state</p><h2>{group?.id ?? "No WorkGroup selected"}</h2>{group && <p>{group.goal || "No goal recorded."}</p>}</div>
            {group && <div className="coordination-header-actions"><StatusBadge value={group.status}/><button className="button button-secondary" type="button" onClick={() => setDetailOpen(true)}>View details</button></div>}
          </div>
          {!group ? <EmptyState icon={GitBranch} title="No workgroups yet" detail="Claims, tasks, and capabilities will appear when a workgroup is projected."/> : (
            <>
              <nav className="sub-tabs" aria-label="Coordination records">{(["claims", "tasks", "capabilities", "ownership"] as CoordinationTab[]).map((item) => <button key={item} className={cx(tab === item && "is-active")} type="button" onClick={() => setTab(item)}>{capitalize(item)}</button>)}</nav>
              <CoordinationTable snapshot={snapshot} tab={tab}/>
            </>
          )}
        </section>
      </div>
      {group && detailOpen && <CoordinationInspector snapshot={snapshot} tab={tab} onClose={() => setDetailOpen(false)}/>}
    </div>
  );
}

function CoordinationTable({snapshot, tab}: {snapshot: Snapshot; tab: CoordinationTab}) {
  if (tab === "claims") return (
    <section className="data-table-wrap panel table-panel">
      <table className="data-table"><thead><tr><th>Claim ID</th><th>Claim Owner</th><th>Role</th><th>State</th><th>Selectors</th><th>Conflicts</th></tr></thead>
        <tbody>{snapshot.claims.length === 0 ? <TableEmpty colSpan={6} text="No claims in the projection."/> : snapshot.claims.map((claim) => <tr key={claim.intentId}><td className="mono">{claim.intentId}</td><td>{claim.participant}</td><td>{claim.role}</td><td><StatusBadge value={claim.state}/></td><td className="table-cell-wrap">{claim.selectors.map(selectorText).join(", ") || "—"}</td><td>{claim.conflicts.length}</td></tr>)}</tbody>
      </table>
    </section>
  );
  if (tab === "tasks") return (
    <section className="data-table-wrap panel table-panel">
      <table className="data-table"><thead><tr><th>Task ID</th><th>Title</th><th>Capability Requested</th><th>Owner</th></tr></thead>
        <tbody>{snapshot.tasks.length === 0 ? <TableEmpty colSpan={4} text="No tasks in the projection."/> : snapshot.tasks.map((task) => <tr key={task.id}><td className="mono">{task.id}</td><td>{task.title}</td><td className="mono">{task.capability}</td><td>{task.owner || "Unassigned"}</td></tr>)}</tbody>
      </table>
    </section>
  );
  if (tab === "capabilities") return (
    <section className="data-table-wrap panel table-panel">
      <table className="data-table"><thead><tr><th>Capability</th><th>Requester</th><th>Owner</th><th>State</th><th>Reason</th></tr></thead>
        <tbody>{snapshot.capabilities.length === 0 ? <TableEmpty colSpan={5} text="No capability requests in the projection."/> : snapshot.capabilities.map((capability) => <tr key={capability.handle}><td className="mono">{capability.capability}</td><td>{capability.requester}</td><td>{capability.owner || "—"}</td><td><StatusBadge value={capability.state}/></td><td className="table-cell-wrap">{capability.reason || "—"}</td></tr>)}</tbody>
      </table>
    </section>
  );
  return (
    <section className="data-table-wrap panel table-panel">
      <table className="data-table"><thead><tr><th>Capability</th><th>Task ID</th><th>Owner</th><th>Protected Scopes</th><th>Intent Version</th></tr></thead>
        <tbody>{snapshot.ownerships.length === 0 ? <TableEmpty colSpan={5} text="No ownership records in the projection."/> : snapshot.ownerships.map((ownership) => <tr key={ownership.capability + ownership.taskId}><td className="mono">{ownership.capability}</td><td className="mono">{ownership.taskId}</td><td>{ownership.owner}</td><td className="table-cell-wrap">{ownership.protectedScopes.join(", ") || "—"}</td><td>{ownership.intentVersion}</td></tr>)}</tbody>
      </table>
    </section>
  );
}

function CoordinationInspector({snapshot, tab, onClose}: {snapshot: Snapshot; tab: CoordinationTab; onClose(): void}) {
  const title = tab === "claims" ? "Claim Detail" : tab === "tasks" ? "Task Detail" : tab === "capabilities" ? "Capability Detail" : "Ownership Detail";
  const claim = snapshot.claims[0];
  const task = snapshot.tasks[0];
  const capability = snapshot.capabilities[0];
  const ownership = snapshot.ownerships[0];
  return (
    <DetailModal title={title} onClose={onClose}>
      {tab === "claims" && claim && <><div className="inspector-title"><h2>{claim.intentId}</h2><StatusBadge value={claim.state}/></div><DetailList><DetailRow label="Claim Owner" value={claim.participant}/><DetailRow label="WorkGroup" value={claim.workGroupId}/><DetailRow label="Role" value={claim.role}/><DetailRow label="Selectors" value={claim.selectors.map(selectorText).join(", ") || "—"}/><DetailRow label="Conflicts" value={String(claim.conflicts.length)}/></DetailList></>}
      {tab === "tasks" && task && <><div className="inspector-title"><h2>{task.id}</h2></div><DetailList><DetailRow label="Title" value={task.title}/><DetailRow label="Capability" value={task.capability}/><DetailRow label="Owner" value={task.owner || "Unassigned"}/></DetailList></>}
      {tab === "capabilities" && capability && <><div className="inspector-title"><h2>{capability.capability}</h2><StatusBadge value={capability.state}/></div><DetailList><DetailRow label="Requester" value={capability.requester}/><DetailRow label="Owner" value={capability.owner || "—"}/><DetailRow label="Reason" value={capability.reason || "—"}/></DetailList><InspectorSection title="Contract"><DetailList><DetailRow label="Inputs" value={capability.contract.inputs.join(", ") || "—"}/><DetailRow label="Output" value={capability.contract.output || "—"}/><DetailRow label="Required Behavior" value={capability.contract.requiredBehavior || "—"}/></DetailList></InspectorSection></>}
      {tab === "ownership" && ownership && <><div className="inspector-title"><h2>{ownership.capability}</h2></div><DetailList><DetailRow label="Task ID" value={ownership.taskId}/><DetailRow label="Owner" value={ownership.owner}/><DetailRow label="Protected Scopes" value={ownership.protectedScopes.join(", ") || "—"}/><DetailRow label="Intent Version" value={String(ownership.intentVersion)}/></DetailList></>}
      {((tab === "claims" && !claim) || (tab === "tasks" && !task) || (tab === "capabilities" && !capability) || (tab === "ownership" && !ownership)) && <p className="muted inspector-copy">No record is available in the current projection.</p>}
    </DetailModal>
  );
}

export function NetworkView({network, onboarding = {pendingJoins: []}, client}: {network: NetworkSnapshot; onboarding?: NonNullable<Snapshot["onboarding"]>; client: ControlPlaneClient | null}) {
  const [invite, setInvite] = useState("");
  const [joinUri, setJoinUri] = useState("");
  const [joinResult, setJoinResult] = useState("");
  const [actionMessage, setActionMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [detail, setDetail] = useState<"membership" | "relay" | null>(null);
  const [connectionTarget, setConnectionTarget] = useState<{peer: NetworkPeerSnapshot; route: NetworkSnapshot["routes"][number] | null; memberStatus: string} | null>(null);
  const [terminationTarget, setTerminationTarget] = useState<{peer: NetworkPeerSnapshot; routeKind: string} | null>(null);

  const createInvite = async () => {
    if (!client) return;
    setBusy(true);
    try {
      const response = await client.invite();
      setInvite(response.inviteUri);
      setActionMessage("Invitation created by the local control plane.");
    } catch (error) {
      setActionMessage(error instanceof Error ? error.message : "Invitation failed.");
    } finally { setBusy(false); }
  };
  const join = async () => {
    if (!client || !joinUri.trim()) return;
    setBusy(true);
    try {
      const response = await client.join(joinUri.trim());
      setJoinResult(response.answerUri);
      setActionMessage("Join request accepted; return the answer URI to the inviter.");
    } catch (error) {
      setActionMessage(error instanceof Error ? error.message : "Join failed.");
    } finally { setBusy(false); }
  };
  const connectJoin = async () => {
    if (!client || onboarding.pendingJoins.length === 0) return;
    setBusy(true);
    try {
      await client.connect(onboarding.pendingJoins[0].operationId);
      setActionMessage("Pending join connected through the local Link runtime.");
    } catch (error) {
      setActionMessage(error instanceof Error ? error.message : "Join connection failed.");
    } finally { setBusy(false); }
  };
  const pendingJoin = onboarding.pendingJoins[0];

  return (
    <div className="view-stack">
      <section className="network-toolbar">
        <div><p className="eyebrow">Project connectivity</p><h2>Link Onboarding</h2></div>
        <div className="toolbar-actions"><button className="button button-secondary" type="button" onClick={() => setDetail("membership")}><Network size={16}/> Membership</button><button className="button button-secondary" type="button" onClick={() => setDetail("relay")}><Link2 size={16}/> Relay detail</button><button className="button button-secondary" type="button" disabled={busy || !client} onClick={() => void createInvite()}><Plus size={16}/> Create Invitation</button><button className="button button-secondary" type="button" disabled={busy || !client || !joinUri.trim()} onClick={() => void join()}><UserRound size={16}/> Join from Invitation</button>{pendingJoin && <button className="button button-primary" type="button" disabled={busy || !client} onClick={() => void connectJoin()}><Check size={16}/> Complete Join</button>}</div>
      </section>
      <section className="onboarding-panel panel">
        <div className="onboarding-grid">
          <div className="form-block"><label htmlFor="join-uri">Invitation URI</label><div className="inline-form"><input id="join-uri" value={joinUri} onChange={(event) => setJoinUri(event.target.value)} placeholder="Paste an invitation URI" /><button className="button button-primary" type="button" disabled={busy || !client || !joinUri.trim()} onClick={() => void join()}>Join</button></div></div>
          <div className="form-block"><span className="form-label">Create Invitation</span><p className="muted">The URI is created by the local runtime and expires according to its response.</p><button className="button button-secondary" type="button" disabled={busy || !client} onClick={() => void createInvite()}><Link2 size={16}/> Create Invitation</button></div>
        </div>
        {invite && <CopyField label="Invitation URI" value={invite}/>}
        {joinResult && <CopyField label="Answer URI" value={joinResult}/>}
        {actionMessage && <p className="action-message" role="status">{actionMessage}</p>}
      </section>
      <div className="network-layout">
        <section className="network-main">
          <section className="panel network-status-panel">
            <div className="section-bar"><div><h2>Network</h2><p>Physical peers, signed membership, and server-selected routes.</p></div><StatusBadge value={network.status}/></div>
            <div className="network-state-row"><span>Overlay State <StatusBadge value={network.overlay.status}/></span><span className="header-meta-divider"/><span>Relay State <StatusBadge value={network.relay.status}/></span></div>
          </section>
          <section className="data-table-wrap panel table-panel">
            <table className="data-table"><thead><tr><th>Peer ID</th><th>Route</th><th>Membership State</th><th>Connection Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>{network.peers.length === 0 ? <TableEmpty colSpan={5} text="No physical peers are projected."/> : network.peers.map((peer) => {
                const route = network.routes.find((item) => item.destinationNodeId === peer.nodeId);
                const member = network.overlay.members.find((item) => item.nodeId === peer.nodeId);
                const reachable = peer.usable && peer.authenticated;
                const openDetails = () => setConnectionTarget({peer, route: route ?? null, memberStatus: member?.status || "UNKNOWN"});
                return <tr key={peer.nodeId + peer.sessionId} tabIndex={0} aria-label={`View connection details for ${peer.nodeId}`} onClick={openDetails} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); openDetails(); } }}><td className="mono">{peer.nodeId}</td><td className="mono table-cell-wrap">{route ? route.kind + (route.nextHop ? " · " + route.nextHop : "") : "—"}</td><td><StatusBadge value={member?.status || "UNKNOWN"}/></td><td><span className={cx("connection-status", reachable ? "is-connected" : "is-unreachable")}><span className="status-dot"/>{peer.liveness || (reachable ? "CONNECTED" : "UNREACHABLE")}</span></td><td className="table-action-cell"><button className="icon-button connection-delete-button" type="button" aria-label={`Terminate connection to ${peer.nodeId}`} title="Terminate connection" onClick={(event) => { event.stopPropagation(); setTerminationTarget({peer, routeKind: route?.kind ?? "UNKNOWN"}); }}><Trash2 size={15}/></button></td></tr>;
              })}</tbody>
            </table>
          </section>
        </section>
      </div>
      {detail === "membership" && <NetworkMembershipModal network={network} onClose={() => setDetail(null)}/>}
      {detail === "relay" && <NetworkRelayModal network={network} onClose={() => setDetail(null)}/>}
      {connectionTarget && <ConnectionDetailModal peer={connectionTarget.peer} route={connectionTarget.route} memberStatus={connectionTarget.memberStatus} onClose={() => setConnectionTarget(null)}/>}
      {terminationTarget && <ConnectionTerminationModal peer={terminationTarget.peer} routeKind={terminationTarget.routeKind} onClose={() => setTerminationTarget(null)}/>}
    </div>
  );
}

function NetworkMembershipModal({network, onClose}: {network: NetworkSnapshot; onClose(): void}) {
  return <DetailModal title="Membership" onClose={onClose}><div className="inspector-title"><StatusBadge value={network.overlay.status}/></div><DetailList><DetailRow label="Authority" value={network.overlay.authorityNodeId || "—"}/><DetailRow label="Revision" value={String(network.overlay.revision)}/><DetailRow label="Members" value={String(network.overlay.memberCount)}/><DetailRow label="Expires" value={formatDate(network.overlay.expiresAt)}/></DetailList></DetailModal>;
}

function NetworkRelayModal({network, onClose}: {network: NetworkSnapshot; onClose(): void}) {
  return <DetailModal title="Relay Detail" onClose={onClose}><div className="inspector-title"><StatusBadge value={network.relay.status}/></div><DetailList><DetailRow label="Local Address" value={network.relay.localAddress || "—"}/><DetailRow label="Relay Identity" value={network.relay.relayIdentity || "—"}/><DetailRow label="Configured" value={network.relay.configured ? "Yes" : "No"}/><DetailRow label="Authorized" value={network.relay.authorized ? "Yes" : "No"}/><DetailRow label="Active Connections" value={String(network.relay.activeConnections)}/></DetailList></DetailModal>;
}

function ConnectionDetailModal({peer, route, memberStatus, onClose}: {peer: NetworkPeerSnapshot; route: NetworkSnapshot["routes"][number] | null; memberStatus: string; onClose(): void}) {
  return (
    <DetailModal title="Connection detail" onClose={onClose}>
      <div className="inspector-title"><span className="eyebrow">Peer connection</span><h2>{peer.nodeId}</h2><StatusBadge value={peer.liveness || "UNKNOWN"}/></div>
      <DetailList>
        <DetailRow label="Peer ID" value={<span className="mono">{peer.nodeId}</span>}/>
        <DetailRow label="Session ID" value={<span className="mono">{peer.sessionId}</span>}/>
        <DetailRow label="Route" value={route?.kind || "No route projected"}/>
        <DetailRow label="Next Hop" value={<span className="mono">{route?.nextHop || "—"}</span>}/>
        <DetailRow label="Path" value={<span className="mono">{route?.path.join(" → ") || "—"}</span>}/>
        <DetailRow label="Membership" value={memberStatus}/>
        <DetailRow label="Health" value={peer.health || "—"}/>
        <DetailRow label="Authenticated" value={peer.authenticated ? "Yes" : "No"}/>
        <DetailRow label="Usable" value={peer.usable ? "Yes" : "No"}/>
        <DetailRow label="Established" value={formatDate(peer.establishedAt)}/>
      </DetailList>
    </DetailModal>
  );
}

function ConnectionTerminationModal({peer, routeKind, onClose}: {peer: NetworkPeerSnapshot; routeKind: string; onClose(): void}) {
  const nonRelay = routeKind === "DIRECT" || routeKind === "PEER_TRANSIT";
  return (
    <DetailModal title="Terminate end of connection?" onClose={onClose}>
      <div className="termination-modal-copy">
        <div className="termination-peer"><Trash2 size={20} aria-hidden="true"/><strong>{peer.nodeId}</strong><StatusBadge value={peer.liveness || "CONNECTED"}/></div>
        <p>Do you want to terminate your end of the connection?</p>
        {nonRelay && <p className="termination-warning">This will cause members that rely on you to momentarily lose connection to other nodes because this is a {routeKind === "PEER_TRANSIT" ? "peer-to-peer passthrough" : "peer-to-peer"} connection.</p>}
        <p className="muted">Termination is not available through the current control-plane API.</p>
      </div>
      <div className="termination-modal-actions"><button className="button button-secondary" type="button" onClick={onClose}>Cancel</button><button className="button button-danger" type="button" disabled title="Termination is not exposed by the current control-plane API">Terminate end</button></div>
    </DetailModal>
  );
}

export function DiagnosticsView({diagnostics}: {diagnostics: Snapshot["diagnostics"]}) {
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const selectedIndex = diagnostics.findings.findIndex((finding) => finding.code === selectedCode);
  const selected = selectedIndex >= 0 ? diagnostics.findings[selectedIndex] : null;
  const overallStatus = diagnostics.overallStatus || "UNKNOWN";
  const hasIssues = diagnostics.criticalCount > 0 || diagnostics.errorCount > 0 || diagnostics.warningCount > 0 || ["warn", "bad"].includes(statusTone(overallStatus));
  const moveSelection = (direction: -1 | 1) => {
    if (selectedIndex < 0) return;
    const nextIndex = Math.max(0, Math.min(diagnostics.findings.length - 1, selectedIndex + direction));
    setSelectedCode(diagnostics.findings[nextIndex]?.code ?? null);
  };
  return (
    <div className="view-stack">
      <section className={cx("diagnostics-summary", "panel", hasIssues ? "has-issues" : "is-quiet")} aria-labelledby="diagnostics-title">
        <div className="diagnostics-summary-main">
          <div className="diagnostics-title-row"><h2 id="diagnostics-title">Diagnostics</h2><StatusBadge value={overallStatus} label={formatDiagnosticValue(overallStatus)}/></div>
          <p className="diagnostics-summary-copy">{hasIssues ? "Issues require attention." : "No issues require attention."}</p>
        </div>
        <div className="diagnostics-report-meta">
          <span><span className="diagnostics-report-label">Report</span><code>{diagnostics.reportId || "—"}</code></span>
          <span>{formatDiagnosticDate(diagnostics.timestampEpochMillis)}</span>
        </div>
        <div className="diagnostics-counts" aria-label="Diagnostic severity counts">
          <span>Critical <strong className={cx(diagnostics.criticalCount > 0 && "tone-bad")}>{diagnostics.criticalCount}</strong></span>
          <span>Errors <strong className={cx(diagnostics.errorCount > 0 && "tone-bad")}>{diagnostics.errorCount}</strong></span>
          <span>Warnings <strong className={cx(diagnostics.warningCount > 0 && "tone-warn")}>{diagnostics.warningCount}</strong></span>
          <span>Info <strong>{diagnostics.infoCount}</strong></span>
        </div>
      </section>
      <section className="data-table-wrap panel table-panel">
        <table className="data-table diagnostics-table"><thead><tr><th>Severity</th><th>Finding</th><th>Confidence</th><th>Component</th><th>Recommendation</th></tr></thead>
          <tbody>{diagnostics.findings.length === 0 ? <TableEmpty colSpan={5} text="No findings require attention."/> : diagnostics.findings.map((finding) => <tr key={finding.code} className={cx(selected?.code === finding.code && "is-selected")} tabIndex={0} aria-selected={selected?.code === finding.code} onClick={() => setSelectedCode(finding.code)} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedCode(finding.code); } }}><td><StatusBadge value={finding.severity} label={formatDiagnosticValue(finding.severity)}/></td><td>{finding.summary}</td><td className="mono">{formatDiagnosticValue(finding.confidence)}</td><td>{finding.affectedResourceType}</td><td className="table-cell-wrap">{formatDiagnosticValue(finding.recommendation)}</td></tr>)}</tbody>
        </table>
      </section>
      {selected && (
        <FindingInspector finding={selected} index={selectedIndex} total={diagnostics.findings.length} onClose={() => setSelectedCode(null)} onNavigate={moveSelection}/>
      )}
    </div>
  );
}

function FindingInspector({finding, index, total, onClose, onNavigate}: {finding: DiagnosticFinding; index: number; total: number; onClose(): void; onNavigate(direction: -1 | 1): void}) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeButtonRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      } else if (event.key === "ArrowLeft" && index > 0) {
        event.preventDefault();
        onNavigate(-1);
      } else if (event.key === "ArrowRight" && index < total - 1) {
        event.preventDefault();
        onNavigate(1);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [index, onClose, onNavigate, total]);

  return (
    <div className="diagnostics-modal" role="presentation" onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="inspector panel diagnostics-modal-card" role="dialog" aria-modal="true" aria-labelledby="finding-detail-title" aria-describedby="finding-detail-explanation" onClick={(event) => event.stopPropagation()}>
        <div className="inspector-heading diagnostics-modal-heading">
          <span>Finding Detail</span>
          <div className="diagnostics-modal-actions">
            <button className="icon-button diagnostics-modal-button" type="button" aria-label="Previous finding" disabled={index <= 0} onClick={() => onNavigate(-1)}><ChevronLeft size={18}/></button>
            <span className="diagnostics-modal-index" aria-live="polite">{index + 1} of {total}</span>
            <button className="icon-button diagnostics-modal-button" type="button" aria-label="Next finding" disabled={index >= total - 1} onClick={() => onNavigate(1)}><ChevronRight size={18}/></button>
            <button ref={closeButtonRef} className="icon-button diagnostics-modal-button" type="button" aria-label="Close finding detail" onClick={onClose}><X size={17}/></button>
          </div>
        </div>
        <div className="inspector-title"><StatusBadge value={finding.severity} label={formatDiagnosticValue(finding.severity)}/><h2 id="finding-detail-title">{finding.summary}</h2></div>
        <DetailList><DetailRow label="Code" value={finding.code}/><DetailRow label="Confidence" value={finding.confidence}/><DetailRow label="Affected Component" value={finding.affectedResourceType}/><DetailRow label="Repair availability" value={finding.repairSupported ? "Available" : "Not available"}/></DetailList>
        <InspectorSection title="Explanation"><p id="finding-detail-explanation" className="inspector-copy">{finding.explanation || "No explanation recorded."}</p></InspectorSection>
        <InspectorSection title="Recommendation"><p className="inspector-copy">{formatDiagnosticValue(finding.recommendation)}</p></InspectorSection>
      </section>
    </div>
  );
}

function ConnectionScreen({state, message}: {state: string; message: string}) {
  return <main className="connection-screen"><section className="connection-card panel"><img className="brand-logo brand-logo-large" src="/synesis-logo.png" alt="" aria-hidden="true"/><p className="eyebrow">Synesis local control plane</p><h1>Connecting to project state</h1><StatusBadge value={state}/><p>{message}</p><div className="loading-line" aria-hidden="true"/></section></main>;
}

function StatusBadge({value, large, label}: {value: string; large?: boolean; label?: string}) {
  return <span className={cx("status-badge", "status-" + statusTone(value), large && "status-large")}><span className="status-dot"/>{label ?? formatStatus(value)}</span>;
}

function OverviewSection({title, icon: Icon, action, children}: {title: string; icon: LucideIcon; action?: ReactNode; children: ReactNode}) {
  return <section className="panel overview-section"><div className="section-bar"><h2><Icon size={17} aria-hidden="true"/>{title}</h2>{action}</div>{children}</section>;
}

function OverviewStat({label, value}: {label: string; value: number}) {
  return <div className="overview-stat"><span>{label}</span><strong>{value}</strong></div>;
}

function BoundaryLine({label, value, tone}: {label: string; value: number; tone: string}) {
  return <div className="boundary-line"><span><span className={cx("boundary-dot", tone)}/>{label}</span><strong>{value}</strong></div>;
}

function TextAction({children, onClick}: {children: ReactNode; onClick(): void}) {
  return <button className="text-action" type="button" onClick={onClick}>{children}<ChevronRight size={14}/></button>;
}

function DetailList({children}: {children: ReactNode}) {
  return <dl className="detail-list">{children}</dl>;
}

function DetailRow({label, value}: {label: string; value: ReactNode}) {
  return <div className="detail-row"><dt>{label}</dt><dd>{value}</dd></div>;
}

function InspectorSection({title, children}: {title: string; children: ReactNode}) {
  return <section className="inspector-section"><h3>{title}</h3>{children}</section>;
}

function DetailModal({title, onClose, children}: {title: string; onClose(): void; children: ReactNode}) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeButtonRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="detail-modal" role="presentation" onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="inspector panel detail-modal-card" role="dialog" aria-modal="true" aria-labelledby="detail-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="inspector-heading"><span id="detail-modal-title">{title}</span><button ref={closeButtonRef} className="icon-button detail-modal-close" type="button" aria-label={`Close ${title.toLowerCase()}`} onClick={onClose}><X size={16}/></button></div>
        {children}
      </section>
    </div>
  );
}

function ValueList({values}: {values: string[]}) {
  return <ul className="value-list">{values.map((value, index) => <li key={value + index}>{value}</li>)}</ul>;
}

function CopyField({label, value}: {label: string; value: string}) {
  const copy = async () => {
    try { await navigator.clipboard.writeText(value); } catch { /* Clipboard access is optional in local browser sessions. */ }
  };
  return <div className="copy-field"><span className="form-label">{label}</span><div><code>{value}</code><button className="icon-button" type="button" aria-label={"Copy " + label} onClick={() => void copy()}><Copy size={15}/></button></div></div>;
}

function EmptyState({icon: Icon, title, detail}: {icon: LucideIcon; title: string; detail: string}) {
  return <div className="empty-state panel"><Icon size={22} aria-hidden="true"/><h2>{title}</h2><p>{detail}</p></div>;
}

function TableEmpty({colSpan, text}: {colSpan: number; text: string}) {
  return <tr><td className="table-empty" colSpan={colSpan}>{text}</td></tr>;
}

function cx(...parts: Array<string | false | null | undefined>) {
  return parts.filter(Boolean).join(" ");
}

function formatStatus(value: string) {
  return (value || "UNKNOWN").replaceAll("_", " ");
}

function formatDate(value: string | number | null | undefined) {
  if (value === null || value === undefined || value === "") return "—";
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return String(value);
  return date.toLocaleString([], {dateStyle: "medium", timeStyle: "short"});
}

function formatDiagnosticDate(value: number) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "—";
  return `${date.toLocaleDateString([], {month: "short", day: "numeric", year: "numeric"})} · ${date.toLocaleTimeString([], {hour: "numeric", minute: "2-digit"})}`;
}

function formatDiagnosticValue(value: string | undefined) {
  const normalized = (value || "").trim().replaceAll("-", "_").toUpperCase();
  const labels: Record<string, string> = {
    CONFIRMED: "Confirmed",
    CRITICAL: "Critical",
    DEGRADED: "Degraded",
    ERROR: "Error",
    INFO: "Info",
    WARNING: "Warning",
    WARN: "Warning",
    HEALTHY: "Healthy",
    NO_ACTION: "No action required",
    NOT_AVAILABLE: "Not available",
    OK: "Healthy",
  };
  return labels[normalized] ?? formatStatus(value || "UNKNOWN");
}

function capitalize(value: string) {
  return value.slice(0, 1).toUpperCase() + value.slice(1);
}

function selectorText(selector: {kind?: string; type?: string; value?: string; [key: string]: unknown}) {
  const kind = selector.kind ?? selector.type ?? "selector";
  const value = selector.value ?? Object.entries(selector).filter(([key]) => key !== "kind" && key !== "type").map(([key, item]) => key + "=" + String(item)).join(", ");
  return kind + (value ? ": " + value : "");
}

function statusTone(value: string): StatusTone {
  const normalized = (value || "").toUpperCase();
  if (["LIVE", "RUNNING", "READY", "ACTIVE", "CONNECTED", "CONFIGURED", "HEALTHY", "OK", "PROJECT_MEMBER", "ESTABLISHED", "SUPPORTED", "SUCCESS"].includes(normalized)) return "good";
  if (["WARNING", "WARN", "DEGRADED", "WAITING", "WAITING_DEP", "CLAIM_HELD", "BLOCKED", "PENDING", "UNCONFIGURED"].includes(normalized)) return "warn";
  if (["ERROR", "CRITICAL", "FAILED", "UNAVAILABLE", "UNREACHABLE", "IDENTITY_MISMATCH", "OFFLINE", "CONFLICT"].includes(normalized)) return "bad";
  return "neutral";
}

function networkDetail(network: NetworkSnapshot) {
  if (network.peers.length === 0) return "No physical peers are currently projected.";
  if (network.peers.some((peer) => !peer.usable)) return "One or more physical peer sessions are not usable.";
  return network.overlay.status === "UNCONFIGURED" ? "Physical sessions exist; signed membership is not configured." : "Physical sessions and membership are visible.";
}
