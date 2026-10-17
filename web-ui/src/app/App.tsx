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
  LayoutDashboard,
  LayoutGrid,
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
  type ProjectLinkDescription,
  ControlPlaneError,
  type DiagnosticFinding,
  type KnownProjectSnapshot,
  type NetworkPeerSnapshot,
  type NetworkSnapshot,
  type ProjectFolderChoice,
  type ProjectSelection,
  type Snapshot,
  clearSelectionId,
  projectLinkFromLocation,
  selectionIdFromLocation,
} from "../api/controlPlane";
import {parseRoute, projectTabs, routePath, type ProjectView, type Route} from "./routes";

export type View = ProjectView | "projects";
type StatusTone = "good" | "warn" | "bad" | "neutral";
type CoordinationTab = "claims" | "tasks" | "capabilities" | "ownership" | "conversations" | "operator";
type ProjectRegistryClient = Pick<ControlPlaneClient, "chooseProject" | "registerProject" | "removeProjects">;
type JoinClient = Pick<ControlPlaneClient, "join" | "openProjectLink" | "chooseProject"> & Partial<Pick<ControlPlaneClient, "describeProjectLink">>;
const PROJECT_LINK_PREFIX = "synesis://project/";

function projectLinkMessage(error: unknown): string {
  const code = error && typeof error === "object" && "code" in error && typeof error.code === "string"
    ? error.code : "";
  if (code === "PROJECT_IDENTITY_MISMATCH") return "That folder holds a different project than the invitation's.";
  if (code === "PROJECT_FOLDER_NOT_THE_PROJECT") return "That folder is not a copy of this project. Clone the project's repository, then choose the clone.";
  if (code === "PROJECT_FOLDER_INVALID") return "That folder could not be found.";
  if (code === "LOG_NOT_EMPTY") return "This copy of the project already has its own work log, so it cannot follow another computer.";
  if (code === "ALREADY_A_MEMBER" || code === "FOLLOWING_ANOTHER_LEADER") return "This computer already follows another computer for this project.";
  if (code === "ALREADY_A_LEADER") return "This computer already leads this project with members of its own.";
  if (code === "INVITER_IS_THIS_COMPUTER") return "This invitation was made on this computer.";
  if (code === "CODE_REMOTE_NAME_TAKEN") return "This copy has a Git remote named synesis that points elsewhere. Rename it, then try again.";
  if (code === "INVITE_INVALID") return "This invitation is not valid or has expired. Ask for a new one.";
  if (code === "PROJECT_RUNTIME_UNAVAILABLE") return "The project's runtime could not be reached on this computer.";
  return error instanceof Error ? error.message : "The invitation could not be opened.";
}

function codeRemoteReasonText(reason: string | null | undefined): string {
  if (reason === "SEVERAL_GIT_REMOTES") return "this project has several Git remotes and none is named code.remote";
  if (reason === "URL_NOT_SHAREABLE") return "the remote's URL is not an https://, ssh:// or user@host:path address";
  if (reason === "CODE_REMOTE_MISSING") return "code.remote names a remote this project does not have";
  return "this project has no Git remote";
}

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
  return snapshot.knownProjects ?? (snapshot.project ? [asLiveRegistryProject(snapshot.project)] : []);
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
  const [projectLink, setProjectLink] = useState("");
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
    removeProjects: async (projectIds: string[]): Promise<Record<string, unknown>> => {
      setSnapshot((current) => current ? {
        ...current,
        knownProjects: current.knownProjects.filter((project) => !projectIds.includes(project.id)),
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
        const pendingSelectionId = selectionIdFromLocation();
        const pendingProjectLink = projectLinkFromLocation();
        if (pendingProjectLink) setProjectLink(pendingProjectLink);
        if (typeof window !== "undefined" && window.location.pathname === "/") {
          const kept = new URLSearchParams();
          if (pendingSelectionId) kept.set("selectionId", pendingSelectionId);
          if (pendingProjectLink) kept.set("projectLink", pendingProjectLink);
          window.history.replaceState(null, "", `/projects${kept.size > 0 ? `#${kept.toString()}` : ""}`);
        }
        // A reloaded tab has no bootstrap token left; it carries on with the session it had.
        const initial = bootstrap ? await client.startSession(bootstrap) : await client.resumeSession();
        if (!initial) throw new Error("Open Synesis with the supported synesis ui command.");
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

  const openProjectView = (view: ProjectView) => navigate({kind: "project", projectId: snapshot.project!.id, view});

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">Skip to content</a>
      <aside className="sidebar">
        <button className="brand-button" type="button" onClick={() => navigate({kind: "projects"})} aria-label="Open Projects registry">
          <img className="brand-logo" src="/synesis-logo.png" alt="" aria-hidden="true" />
          <span className="brand-name">Synesis</span>
        </button>
        <nav className="sidebar-nav" aria-label="Workspace">
          <NavItem icon={LayoutGrid} label="Projects" count={registryProjects(snapshot).length} active={route.kind === "projects"} onClick={() => navigate({kind: "projects"})}/>
        </nav>
        {route.kind === "project" && liveProjectAvailable && <>
          <p className="sidebar-heading" title={snapshot.project?.path}>{snapshot.project?.name ?? "Project"}</p>
          <ProjectTabs snapshot={snapshot} view={route.view} onNavigate={openProjectView}/>
        </>}
        <div className="sidebar-footer">
          <span className={cx("connection-status", connection === "LIVE" ? "is-connected" : "is-unreachable")} role="status"><span className="status-dot"/>{message}</span>
          {snapshot.runtime.version && <span className="mono sidebar-version">v{snapshot.runtime.version}</span>}
        </div>
      </aside>

      <main id="main-content" className="main-content">
        {route.kind === "projects" ? (
          <div className="page-frame is-wide">
            <ProjectsView
              snapshot={snapshot}
              client={registryClient}
              joinClient={mockMode ? null : clientRef.current}
              initialProjectLink={projectLink}
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
          <div className={cx("page-frame", (route.view === "coordination" || route.view === "network") && "is-wide")}>
            <ProjectHeader snapshot={snapshot} title={snapshot.project?.name ?? "Project"} onBack={() => navigate({kind: "projects"})}/>
            {route.view === "overview" && <ProjectOverviewView snapshot={snapshot} onNavigate={openProjectView}/>}
            {route.view === "agents" && <AgentsView agents={snapshot.agents}/>}
            {route.view === "coordination" && <CoordinationView snapshot={snapshot}/>}
            {route.view === "network" && <NetworkView network={snapshot.network} onboarding={snapshot.onboarding} projectName={snapshot.project?.name} client={clientRef.current}/>}
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

export function ProjectsView({snapshot, client, joinClient, initialProjectLink, onSnapshotRefresh, onOpenProject, onOpenNetwork}: {snapshot: Snapshot; client?: ProjectRegistryClient | null; joinClient?: JoinClient | null; initialProjectLink?: string; onSnapshotRefresh?: () => Promise<void>; onOpenProject?: (project: KnownProjectSnapshot) => void; onOpenNetwork?: () => void}) {
  const projects = registryProjects(snapshot);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<KnownProjectSnapshot["status"] | "ALL">("ALL");
  const [addOpen, setAddOpen] = useState(false);
  const [addPath, setAddPath] = useState("");
  const [addProjectName, setAddProjectName] = useState("");
  const [removeTargets, setRemoveTargets] = useState<KnownProjectSnapshot[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
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

  // Every entry can go, the project serving this page included: only the registry forgets it.
  const selectableProjects = client ? filteredProjects : [];
  const selectedProjects = selectableProjects.filter((project) => selectedIds.includes(project.id));
  const allSelected = selectableProjects.length > 0 && selectedProjects.length === selectableProjects.length;
  const askToRemove = (targets: KnownProjectSnapshot[]) => {
    setMutationError("");
    setRemovalError("");
    setRemoveTargets(targets);
  };

  const removeProjects = () => {
    if (!client || removeTargets.length === 0 || mutationBusy) return;
    const targets = removeTargets;
    const targetIds = targets.map((project) => project.id);
    setRemoveTargets([]);
    setMutationError("");
    setSelectedIds((current) => current.filter((id) => !targetIds.includes(id)));
    setPendingRemovalIds((current) => [...current, ...targetIds.filter((id) => !current.includes(id))]);
    void (async () => {
      // The registry drops the whole selection in one durable write, so the rows can go at once
      // and a reload agrees. They come back only when that write is refused.
      try {
        await client.removeProjects(targetIds);
        await refreshRegistry();
      } catch (error) {
        setRemovalError(projectRegistryMessage(error));
      } finally {
        setPendingRemovalIds((current) => current.filter((id) => !targetIds.includes(id)));
      }
    })();
  };

  return (
    <div className="view-stack">
      <header className="page-header">
        <div>
          <h1 id="registry-title">Projects</h1>
          <p>Open a workspace, inspect its runtime, or bring another local folder into Synesis.</p>
        </div>
        {client && <button className="button button-primary" type="button" onClick={() => { setMutationError(""); setRemovalError(""); setAddPath(""); setAddProjectName(""); setAddOpen(true); }}><Plus size={16} aria-hidden="true"/>Add project</button>}
      </header>
      {joinClient && <section className="onboarding panel" aria-label="Join a shared project"><JoinPanel client={joinClient} initialLink={initialProjectLink} onRegistered={refreshRegistry}/></section>}
      <div className="stat-strip" aria-label={projects.length + " known projects, " + liveCount + " live"}>
        <Stat label="Known projects" value={projects.length}/>
        <Stat label="Live runtimes" value={liveCount} tone="good"/>
        <Stat label="Peer connections" value={connectionCount}/>
      </div>

      <div className="registry-columns">
        <section className="registry-section" aria-labelledby="registry-list-title">
          <div className="toolbar">
            <h2 id="registry-list-title">All projects</h2>
            {selectableProjects.length > 1 && <label className="select-all"><input type="checkbox" checked={allSelected} onChange={() => setSelectedIds(allSelected ? [] : selectableProjects.map((project) => project.id))}/>Select all</label>}
            {projects.length > 0 && <p className="toolbar-count" aria-live="polite">Showing <strong>{filteredProjects.length}</strong> of <strong>{visibleProjects.length}</strong> {visibleProjects.length === 1 ? "project" : "projects"}</p>}
            <label className="search-field"><Search size={15} aria-hidden="true"/><input aria-label="Search projects" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Name, path, or identity" type="search" /></label>
            <select className="select-field" aria-label="Filter projects by status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as typeof statusFilter)}>
              <option value="ALL">All statuses</option>
              <option value="LIVE">Live</option>
              <option value="INACTIVE">Inactive</option>
              <option value="UNAVAILABLE">Unavailable</option>
              <option value="IDENTITY_MISMATCH">Identity mismatch</option>
            </select>
          </div>
          {visibleProjects.length === 0 && pendingRemovalIds.length > 0 ? (
            <EmptyState icon={Trash2} title="Removal in progress" detail="The project is hidden while the registry confirms the removal."/>
          ) : projects.length === 0 ? (
            <EmptyState icon={FolderOpen} title="No projects yet" detail="Projects appear after legitimate Synesis discovery through init, runtime startup, or MCP attachment."/>
          ) : filteredProjects.length === 0 ? (
            <EmptyState icon={Search} title="No matching projects" detail="Try a different project name, identity, path, or status."/>
          ) : (
            <div className={cx("project-list", "panel", selectableProjects.length > 1 && "is-selectable")} role="list" aria-label="Known projects">
              {selectedProjects.length > 0 && <div className="selection-bar" role="status">
                <span><strong>{selectedProjects.length}</strong> selected</span>
                <button className="text-action" type="button" onClick={() => setSelectedIds([])}>Clear</button>
                <button className="button button-danger" type="button" onClick={() => askToRemove(selectedProjects)}><Trash2 size={15} aria-hidden="true"/>Remove selected</button>
              </div>}
              {filteredProjects.map((project) => (
                <div className={cx("project-row", selectableProjects.length > 1 && selectedIds.includes(project.id) && "is-selected")} key={project.id + project.path} role="listitem">
                  {selectableProjects.length > 1 && <input type="checkbox" aria-label={`Select ${project.name}`} checked={selectedIds.includes(project.id)} onChange={() => setSelectedIds((current) => current.includes(project.id) ? current.filter((id) => id !== project.id) : [...current, project.id])}/>}
                  <span className={cx("project-mark", "tone-" + statusTone(project.status))} aria-hidden="true"><FolderOpen size={17}/></span>
                  <div className="project-row-main">
                    {onOpenProject ? <button className="project-name" type="button" onClick={() => onOpenProject(project)}>{project.name}</button> : <strong className="project-name">{project.name}</strong>}
                    <span className="mono project-path" title={project.path}>{project.path}</span>
                    {project.status !== "LIVE" && <span className="project-row-note">{registryStateNote(project.status)}</span>}
                  </div>
                  <span className="mono project-id" title="Project identity">{project.id}</span>
                  <StatusBadge value={project.status}/>
                  <div className="project-row-actions">
                    {onOpenProject && project.status === "LIVE" && project.id === snapshot.project?.id
                      ? <button className="text-action" type="button" onClick={() => onOpenProject(project)}>Open project <ChevronRight size={14}/></button>
                      : onOpenProject && project.status !== "LIVE"
                        ? <button className="text-action" type="button" onClick={() => onOpenProject(project)}>View details <ChevronRight size={14}/></button>
                        : <span className="muted">{project.status === "LIVE" ? "Runtime not attached" : "Registry detail"}</span>}
                    {client && <button className="icon-button is-danger" type="button" aria-label={`Remove ${project.name} from registry`} title="Remove from registry" onClick={() => askToRemove([project])}><Trash2 size={15} aria-hidden="true"/></button>}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
        <ConnectionsRail snapshot={snapshot} onOpenNetwork={onOpenNetwork}/>
      </div>
      {addOpen && <DetailModal title="Add project to registry" onClose={() => { if (!mutationBusy) setAddOpen(false); }}>
        <form className="registry-form" onSubmit={(event) => { event.preventDefault(); void addProject(); }}>
          <p className="muted">Choose a folder to add. Existing state is preserved.</p>
          {mutationError && <div className="registry-folder-error" role="alert"><p>{mutationError}</p><button className="icon-button" type="button" aria-label="Dismiss project folder error" onClick={() => setMutationError("")}><X size={15} aria-hidden="true"/></button></div>}
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
      {removeTargets.length > 0 && <DetailModal title={removeTargets.length === 1 ? "Remove project from registry" : "Remove projects from registry"} onClose={() => { if (!mutationBusy) setRemoveTargets([]); }}>
        <div className="registry-form">
          {removeTargets.length === 1
            ? <p>Remove <strong>{removeTargets[0].name}</strong> from the local project registry?</p>
            : <><p>Remove these <strong>{removeTargets.length} projects</strong> from the local project registry?</p><ul className="value-list removal-list">{removeTargets.map((project) => <li key={project.id + project.path}>{project.name}</li>)}</ul></>}
          {removeTargets.some((project) => project.id === snapshot.project?.id) && <p className="muted"><strong>{snapshot.project?.name}</strong> is serving this page. It keeps running, and is remembered again the next time a Synesis command runs in it.</p>}
          <p className="muted">Only the registry {removeTargets.length === 1 ? "entry is" : "entries are"} removed. The project {removeTargets.length === 1 ? "folder" : "folders"}, `.synesis` state, and Link identity stay untouched.</p>
          <div className="registry-form-actions"><button className="button button-secondary" type="button" onClick={() => setRemoveTargets([])} disabled={mutationBusy}>Cancel</button><button className="button button-danger" type="button" onClick={removeProjects} disabled={mutationBusy}>{removeTargets.length === 1 ? "Remove from registry" : `Remove ${removeTargets.length} projects`}</button></div>
        </div>
      </DetailModal>}
      {removalError && <div className="selection-toast" role="alert">{removalError}</div>}
    </div>
  );
}

function ConnectionsRail({snapshot, onOpenNetwork}: {snapshot: Snapshot; onOpenNetwork?: () => void}) {
  const peers = snapshot.network.peers;
  const [terminationTarget, setTerminationTarget] = useState<{peer: NetworkPeerSnapshot; routeKind: string} | null>(null);
  const [connectionTarget, setConnectionTarget] = useState<{peer: NetworkPeerSnapshot; route: NetworkSnapshot["routes"][number] | null; memberStatus: string} | null>(null);
  return (
    <aside className="connections-rail" aria-label="Connections">
      <div className="toolbar">
        <h2>Connections</h2>
        <span className="count-badge">{peers.length}</span>
      </div>
      <section className="panel">
        <p className="rail-caption">Current runtime connections</p>
        {peers.length === 0 ? <div className="rail-empty"><Network size={20} aria-hidden="true"/><h3>No active peers</h3><p>No peer connections are currently projected.</p>{onOpenNetwork && <button className="text-action" type="button" onClick={onOpenNetwork}>Open network <ChevronRight size={14}/></button>}</div> : (
          <div className="connection-list">
            {peers.map((peer) => {
              const route = snapshot.network.routes.find((item) => item.destinationNodeId === peer.nodeId);
              const connected = peer.usable && peer.authenticated;
              return (
                <div className="connection-row" key={peer.nodeId + peer.sessionId}>
                  <button className="connection-detail-button" type="button" aria-label={`View connection details for ${peer.nodeId}`} onClick={() => setConnectionTarget({peer, route: route ?? null, memberStatus: snapshot.network.overlay.members.find((item) => item.nodeId === peer.nodeId)?.status ?? "UNKNOWN"})}>
                    <strong className="mono">{peer.nodeId}</strong>
                    <span className={cx("connection-status", connected ? "is-connected" : "is-unreachable")}><span className="status-dot"/>{peer.liveness || (connected ? "CONNECTED" : "UNREACHABLE")}</span>
                    <span className="connection-route">{route?.kind ?? "No route projected"}</span>
                  </button>
                  <div className="connection-actions">
                    {onOpenNetwork && <button className="text-action" type="button" onClick={onOpenNetwork}>View network <ChevronRight size={14}/></button>}
                    <button className="icon-button is-danger" type="button" aria-label={`Terminate connection to ${peer.nodeId}`} title="Terminate connection" onClick={() => setTerminationTarget({peer, routeKind: route?.kind ?? "UNKNOWN"})}><Trash2 size={15}/></button>
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
    <section className="project-header" aria-label="Project identity">
      <button className="back-link" type="button" aria-label="Back to projects" onClick={onBack}><ArrowLeft size={14} aria-hidden="true"/>Projects</button>
      <div className="project-header-title">
        <h1>{title}</h1>
        <StatusBadge value={project ? snapshot.runtime.status : "UNAVAILABLE"}/>
      </div>
      <dl className="project-header-meta">
        <div><dt>Project ID</dt><dd className="mono" title={project?.id}>{project?.id ?? "—"}</dd></div>
        <div><dt>Local path</dt><dd className="mono" title={project?.path}>{project?.path ?? "—"}</dd></div>
      </dl>
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
    <section className="view-stack">
      <div className="project-header">
        <button className="back-link" type="button" aria-label="Back to projects" onClick={onBack}><ArrowLeft size={14} aria-hidden="true"/>Projects</button>
        <div className="project-header-title">
          <h1>{title}</h1>
          <StatusBadge value={status}/>
        </div>
        {project && <dl className="project-header-meta">
          <div><dt>Project ID</dt><dd className="mono">{project.id}</dd></div>
          <div><dt>Local path</dt><dd className="mono">{project.path}</dd></div>
        </dl>}
      </div>
      <div className={cx("notice", "panel", "tone-" + statusTone(status))}>
        <h2>{status === "IDENTITY_MISMATCH" ? "Identity mismatch" : status === "UNAVAILABLE" ? "Project location unavailable" : status === "INACTIVE" ? "Runtime unavailable" : "Live state unavailable"}</h2>
        <p>{detail}</p>
      </div>
    </section>
  );
}

const projectTabIcons: Record<ProjectView, LucideIcon> = {
  overview: LayoutDashboard,
  agents: Users,
  coordination: GitBranch,
  network: Network,
  diagnostics: ShieldCheck,
};

function ProjectTabs({snapshot, view, onNavigate}: {snapshot: Snapshot; view: ProjectView; onNavigate(view: ProjectView): void}) {
  const findings = snapshot.diagnostics.criticalCount + snapshot.diagnostics.errorCount + snapshot.diagnostics.warningCount;
  const counts: Partial<Record<ProjectView, number>> = {
    agents: snapshot.agents.length,
    coordination: snapshot.workgroups.length,
    network: snapshot.network.peers.length,
    diagnostics: findings,
  };
  return (
    <nav className="sidebar-nav" aria-label="Project views">
      {projectTabs.map((tab) => (
        <NavItem key={tab.id} icon={projectTabIcons[tab.id]} label={tab.label} count={counts[tab.id]} warn={tab.id === "diagnostics" && findings > 0} active={view === tab.id} onClick={() => onNavigate(tab.id)}/>
      ))}
    </nav>
  );
}

function NavItem({icon: Icon, label, count, warn, active, onClick}: {icon: LucideIcon; label: string; count?: number; warn?: boolean; active: boolean; onClick(): void}) {
  return (
    <button className={cx("nav-item", active && "is-active")} type="button" onClick={onClick} aria-current={active ? "page" : undefined}>
      <Icon size={16} aria-hidden="true"/>
      <span>{label}</span>
      {count !== undefined && count > 0 && <span className={cx("nav-count", warn && "tone-warn")} aria-hidden="true">{count}</span>}
    </button>
  );
}

function Stat({label, value, tone}: {label: string; value: ReactNode; tone?: StatusTone}) {
  return <div className="stat"><span>{label}</span><strong className={cx(tone && "tone-" + tone)}>{value}</strong></div>;
}

function ProjectOverviewView({snapshot, onNavigate}: {snapshot: Snapshot; onNavigate(view: Exclude<View, "projects">): void}) {
  const authenticatedPeers = snapshot.network.peers.filter((peer) => peer.authenticated).length;
  return (
    <div className="view-stack">
      <section className="metric-grid" aria-label="Project status">
        <OverviewMetric icon={Activity} label="Providers">
          {snapshot.providers.length === 0 ? <span className="muted">No provider records</span> : <div className="metric-provider-list">{snapshot.providers.map((provider) => <div className="metric-provider" key={provider.id}><span>{provider.id}</span><StatusBadge value={provider.status}/></div>)}</div>}
        </OverviewMetric>
        <OverviewMetric icon={Users} label="Participants" value={snapshot.runtime.activeAgentCount} detail="Active agents in the current runtime"/>
        <OverviewMetric icon={GitBranch} label="Active WorkGroups" value={snapshot.workgroups.length} detail="WorkGroups in this project"/>
        <OverviewMetric icon={Network} label="Authenticated Peers" value={authenticatedPeers} detail="Peers with authenticated access"/>
        <OverviewMetric icon={ShieldCheck} label="Doctor" value={<StatusBadge value={snapshot.diagnostics.overallStatus || "UNKNOWN"}/>} detail="Current diagnostic projection"/>
      </section>

      <div className="overview-grid">
        <OverviewSection title="Coordination" icon={GitBranch} action={<TextAction onClick={() => onNavigate("coordination")}>Open coordination</TextAction>}>
          <div className="overview-two-column">
            <div>
              {snapshot.workgroups.length === 0 ? <><h3>No workgroups in this project</h3><p className="muted">Create workgroups to enable distributed agents, tasks, and shared capabilities.</p></> : <><h3>WorkGroups</h3><div className="overview-list">{snapshot.workgroups.slice(0, 4).map((group) => <div className="overview-list-row" key={group.id}><span className="mono">{group.id}</span><StatusBadge value={group.status}/></div>)}</div></>}
            </div>
            <div>
              <h3>Projection</h3>
              <div className="overview-list">
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
              <div className="overview-list">
                <OverviewStat label="Physical peers" value={snapshot.network.peers.length}/>
                <OverviewStat label="Signed membership" value={snapshot.network.overlay.memberCount}/>
                <OverviewStat label="Server-selected routes" value={snapshot.network.routes.length}/>
              </div>
            </div>
            <div className="network-overview-copy">
              <StatusBadge value={snapshot.network.status}/>
              <p className="muted">{networkDetail(snapshot.network)}</p>
            </div>
          </div>
        </OverviewSection>
      </div>
    </div>
  );
}

function OverviewMetric({icon: Icon, label, value, detail, children}: {icon: LucideIcon; label: string; value?: ReactNode; detail?: string; children?: ReactNode}) {
  return (
    <div className="metric panel">
      <span className="metric-label"><Icon size={15} aria-hidden="true"/>{label}</span>
      {children ?? <strong className="metric-value">{value}</strong>}
      {detail && <span className="metric-detail">{detail}</span>}
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
        <span className="count-badge">{agents.length} participants</span>
      </div>
      {agents.length === 0 ? <EmptyState icon={Users} title="No agents yet" detail="Agent state will appear after a provider session is attached to this project."/> : (
        <section className="data-table-wrap panel" aria-label="Agents table">
          <table className="data-table">
            <thead><tr><th>Participant Identity</th><th>Provider</th><th>Goal</th><th>Coordination State</th><th>Current Work</th><th>Claims</th><th>Last Verified Activity</th></tr></thead>
            <tbody>{agents.map((agent) => (
              <tr key={agent.id} className={cx(selected?.id === agent.id && "is-selected")} tabIndex={0} aria-selected={selected?.id === agent.id} onClick={() => setSelectedId(agent.id)} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedId(agent.id); } }}>
                <td><strong>{agent.id}</strong></td>
                <td><span className="provider-token"><span className="provider-mark" aria-hidden="true">{agent.provider.slice(0, 2).toUpperCase()}</span>{agent.provider}</span></td>
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
        <aside className="workgroup-rail" aria-label="WorkGroups">
          <div className="toolbar"><h2>WorkGroups</h2><span className="count-badge">{snapshot.workgroups.length}</span></div>
          <div className="workgroup-list panel">
            {snapshot.workgroups.length === 0 ? <p className="muted">No workgroups.</p> : snapshot.workgroups.map((item) => (
              <button key={item.id} type="button" className={cx("workgroup-item", group?.id === item.id && "is-selected")} onClick={() => setSelectedGroupId(item.id)}>
                <strong className="mono">{item.id}</strong><span><StatusBadge value={item.status}/><span>{item.participants.length} participants</span></span>
              </button>
            ))}
          </div>
        </aside>
        <section className="coordination-main">
          <div className="view-intro">
            <div><p className="eyebrow">Coordination state</p><h2>{group?.id ?? "No WorkGroup selected"}</h2>{group && <p>{group.goal || "No goal recorded."}</p>}</div>
            {group && <div className="coordination-header-actions"><StatusBadge value={group.status}/><button className="button button-secondary" type="button" onClick={() => setDetailOpen(true)}>View details</button></div>}
          </div>
          {!group ? <EmptyState icon={GitBranch} title="No workgroups yet" detail="Claims, tasks, and capabilities will appear when a workgroup is projected."/> : (
            <>
              <nav className="sub-tabs" aria-label="Coordination records">{(["claims", "tasks", "capabilities", "ownership", "conversations", "operator"] as CoordinationTab[]).map((item) => <button key={item} className={cx(tab === item && "is-active")} type="button" onClick={() => setTab(item)}>{capitalize(item)}</button>)}</nav>
              <CoordinationTable snapshot={snapshot} tab={tab}/>
            </>
          )}
        </section>
      </div>
      {group && detailOpen && <CoordinationInspector snapshot={snapshot} tab={tab} onClose={() => setDetailOpen(false)}/>}
    </div>
  );
}

function memberText(member: {participant?: string; claims?: string[]; lane?: string}): string {
  const ended = member.lane === "ended" ? " (ended)" : "";
  return member.participant ? member.participant + (member.claims?.length ? " (" + member.claims.join(", ") + ")" : "") + ended : "ended lane";
}

function CoordinationTable({snapshot, tab}: {snapshot: Snapshot; tab: CoordinationTab}) {
  if (tab === "claims") return (
    <section className="data-table-wrap panel">
      <table className="data-table"><thead><tr><th>Claim ID</th><th>Claim Owner</th><th>Role</th><th>State</th><th>Selectors</th><th>Conflicts</th></tr></thead>
        <tbody>{snapshot.claims.length === 0 ? <TableEmpty colSpan={6} text="No claims in the projection."/> : snapshot.claims.map((claim) => <tr key={claim.intentId}><td className="mono">{claim.intentId}</td><td>{claim.participant}</td><td>{claim.role}</td><td><StatusBadge value={claim.state}/></td><td className="table-cell-wrap">{claim.selectors.map(selectorText).join(", ") || "—"}</td><td>{claim.conflicts.length}</td></tr>)}</tbody>
      </table>
    </section>
  );
  if (tab === "tasks") return (
    <section className="data-table-wrap panel">
      <table className="data-table"><thead><tr><th>Task ID</th><th>Title</th><th>Capability Requested</th><th>Owner</th></tr></thead>
        <tbody>{snapshot.tasks.length === 0 ? <TableEmpty colSpan={4} text="No tasks in the projection."/> : snapshot.tasks.map((task) => <tr key={task.id}><td className="mono">{task.id}</td><td>{task.title}</td><td className="mono">{task.capability}</td><td>{task.owner || "Unassigned"}</td></tr>)}</tbody>
      </table>
    </section>
  );
  if (tab === "conversations") return (
    <section className="data-table-wrap panel">
      <table className="data-table"><thead><tr><th>Members</th><th>From</th><th>Sent</th><th>Message</th></tr></thead>
        <tbody>{snapshot.conversations.length === 0 ? <TableEmpty colSpan={4} text="No agent has sent a message."/> : snapshot.conversations.flatMap((conversation) => conversation.messages.map((message, index) => <tr key={conversation.conversation + index}><td className="table-cell-wrap">{index === 0 ? conversation.members.map(memberText).join(", ") : ""}</td><td>{memberText(message.from)}</td><td>{new Date(message.sentAtEpochMillis).toLocaleTimeString()}</td><td className="table-cell-wrap">{message.text}</td></tr>))}</tbody>
      </table>
    </section>
  );
  if (tab === "operator") return (
    <section className="data-table-wrap panel">
      <table className="data-table"><thead><tr><th>Request</th><th>Condition</th><th>Raised By</th><th>Summary</th><th>Evidence</th></tr></thead>
        <tbody>{snapshot.operatorRequests.length === 0 ? <TableEmpty colSpan={5} text="Nothing is waiting on an operator."/> : snapshot.operatorRequests.map((request) => <tr key={request.id}><td className="mono">{request.id}</td><td>{request.condition}</td><td>{request.raisedBy ? "A lane" : "Synesis"}</td><td className="table-cell-wrap">{request.summary}</td><td className="table-cell-wrap">{request.evidence}</td></tr>)}</tbody>
      </table>
    </section>
  );
  if (tab === "capabilities") return (
    <section className="data-table-wrap panel">
      <table className="data-table"><thead><tr><th>Capability</th><th>Requester</th><th>Owner</th><th>State</th><th>Reason</th></tr></thead>
        <tbody>{snapshot.capabilities.length === 0 ? <TableEmpty colSpan={5} text="No capability requests in the projection."/> : snapshot.capabilities.map((capability) => <tr key={capability.handle}><td className="mono">{capability.capability}</td><td>{capability.requester}</td><td>{capability.owner || "—"}</td><td><StatusBadge value={capability.state}/></td><td className="table-cell-wrap">{capability.reason || "—"}</td></tr>)}</tbody>
      </table>
    </section>
  );
  return (
    <section className="data-table-wrap panel">
      <table className="data-table"><thead><tr><th>Capability</th><th>Task ID</th><th>Owner</th><th>Protected Scopes</th><th>Intent Version</th></tr></thead>
        <tbody>{snapshot.ownerships.length === 0 ? <TableEmpty colSpan={5} text="No ownership records in the projection."/> : snapshot.ownerships.map((ownership) => <tr key={ownership.capability + ownership.taskId}><td className="mono">{ownership.capability}</td><td className="mono">{ownership.taskId}</td><td>{ownership.owner}</td><td className="table-cell-wrap">{ownership.protectedScopes.join(", ") || "—"}</td><td>{ownership.intentVersion}</td></tr>)}</tbody>
      </table>
    </section>
  );
}

function CoordinationInspector({snapshot, tab, onClose}: {snapshot: Snapshot; tab: CoordinationTab; onClose(): void}) {
  const title = tab === "claims" ? "Claim Detail" : tab === "tasks" ? "Task Detail" : tab === "capabilities" ? "Capability Detail" : tab === "ownership" ? "Ownership Detail" : "Detail";
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
      {(tab === "conversations" || tab === "operator") && <p className="muted inspector-copy">The table shows every record of this kind in full.</p>}
    </DetailModal>
  );
}

function shortNode(nodeId: string): string {
  return nodeId.length > 16 ? nodeId.slice(0, 12) + "…" : nodeId;
}

/**
 * Opens an invitation another computer made: a project invitation finds the project's folder on this computer (asking
 * for it when the project is not registered here), follows the inviter and connects; the owner then has to accept.
 * A plain connection invitation is joined as before.
 */
function JoinPanel({client, initialLink = "", onRegistered}: {client: JoinClient | null; initialLink?: string; onRegistered?: () => Promise<void> | void}) {
  const [link, setLink] = useState(initialLink);
  const [folder, setFolder] = useState("");
  const [folderNeeded, setFolderNeeded] = useState<{url: string | null} | null>(null);
  const [answerUri, setAnswerUri] = useState("");
  const [message, setMessage] = useState("");
  const [problem, setProblem] = useState("");
  const [codeRemoteProblem, setCodeRemoteProblem] = useState("");
  const [busy, setBusy] = useState(false);
  const [details, setDetails] = useState<ProjectLinkDescription | null>(null);
  useEffect(() => {
    if (initialLink) setLink(initialLink);
  }, [initialLink]);
  // What the invitation says is shown before Join, so the person sees whose project it is and where code comes from.
  useEffect(() => {
    const text = link.trim();
    setDetails(null);
    if (!client || !text.startsWith(PROJECT_LINK_PREFIX) || typeof client.describeProjectLink !== "function") return;
    let current = true;
    const timer = setTimeout(() => {
      client.describeProjectLink?.(text).then((described) => { if (current) setDetails(described); }).catch(() => undefined);
    }, 150);
    return () => { current = false; clearTimeout(timer); };
  }, [client, link]);

  const open = async (chosen?: string) => {
    const text = link.trim();
    if (!client || !text || busy) return;
    setBusy(true);
    setProblem("");
    setMessage("");
    setCodeRemoteProblem("");
    try {
      if (!text.startsWith(PROJECT_LINK_PREFIX)) {
        const response = await client.join(text);
        setFolderNeeded(null);
        setAnswerUri(response.answerUri);
        setMessage("Connecting. If it does not connect within a few seconds, send the answer link below to the person who invited you.");
        return;
      }
      const response = await client.openProjectLink(text, chosen);
      if (response.state === "PROJECT_NOT_FOUND") {
        setAnswerUri("");
        setFolderNeeded({url: response.codeRemoteUrl ?? null});
        return;
      }
      setFolderNeeded(null);
      setAnswerUri(response.answerUri ?? "");
      setMessage("Connecting to the owner of the project. They have to accept this computer before any work is shared. If it does not connect within a few seconds, send the answer link below to the person who invited you.");
      // The join goes on: the person can fix this afterwards, and agents here cannot start until they do.
      if (response.codeRemoteCheck && response.codeRemoteCheck !== "REACHABLE") {
        setCodeRemoteProblem(`This computer cannot fetch the project's code without a sign-in, so agents here cannot start yet. ${response.codeRemoteDetail ?? response.codeRemoteCheck}`);
      }
      await onRegistered?.();
    } catch (error) {
      setProblem(projectLinkMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const chooseFolder = async () => {
    if (!client || busy) return;
    try {
      const choice = await client.chooseProject();
      if (choice.state === "SELECTED") setFolder(choice.path);
    } catch (error) {
      setProblem(projectLinkMessage(error));
    }
  };

  return (
    <div className="form-block">
      <label className="form-label" htmlFor="join-uri">Join from an invitation</label>
      <p className="muted">Paste the invitation another computer created for you.</p>
      <div className="inline-form">
        <input className="text-field" id="join-uri" value={link} onChange={(event) => setLink(event.target.value)} placeholder="Paste an invitation link" />
        <button className="button button-secondary" type="button" disabled={busy || !client || !link.trim()} onClick={() => void open()}><UserRound size={15}/> Join</button>
      </div>
      {details && <div className="form-block" role="group" aria-label="About this invitation">
        <p>From computer <code>{shortNode(details.hostNodeId)}</code> for the project <strong>{details.projectName ?? details.projectId}</strong>.</p>
        <p>{details.codeRemoteUrl ? <>Code is fetched from <code>{details.codeRemoteUrl}</code>.</> : "It names no code remote, so code cannot move between you until the owner adds one."}</p>
        <p className="muted">Expires {new Date(details.expiresAt).toLocaleString()}.</p>
      </div>}
      {folderNeeded && <div className="form-block" role="group" aria-label="Where is the project?">
        <p role="status">This project is not on this computer yet. {folderNeeded.url
          ? <>Clone it from <code>{folderNeeded.url}</code>, then choose the folder you cloned it into.</>
          : "Choose the folder where you have it."}</p>
        <label className="form-label" htmlFor="project-folder">Project folder</label>
        <div className="inline-form">
          <input className="text-field" id="project-folder" value={folder} onChange={(event) => setFolder(event.target.value)} placeholder="Folder that holds the project" />
          {client && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void chooseFolder()}><FolderOpen size={15}/> Choose folder</button>}
          <button className="button button-secondary" type="button" disabled={busy || !folder.trim()} onClick={() => void open(folder.trim())}><Check size={15}/> Use this folder</button>
        </div>
      </div>}
      {answerUri && <CopyField label="Answer link" value={answerUri}/>}
      {message && <p className="action-message" role="status">{message}</p>}
      {codeRemoteProblem && <p className="action-message selection-error" role="alert">{codeRemoteProblem}</p>}
      {problem && <p className="action-message selection-error" role="alert">{problem}</p>}
    </div>
  );
}

export function NetworkView({network, onboarding, projectName, client}: {network: NetworkSnapshot; onboarding?: NonNullable<Snapshot["onboarding"]>; projectName?: string; client: ControlPlaneClient | null}) {
  const [invite, setInvite] = useState("");
  const [inviteOperation, setInviteOperation] = useState("");
  const [answerUri, setAnswerUri] = useState("");
  const [openRouterPort, setOpenRouterPort] = useState(false);
  const [decided, setDecided] = useState<string[]>([]);
  const [actionMessage, setActionMessage] = useState("");
  const [versionMismatch, setVersionMismatch] = useState(false);
  const [busy, setBusy] = useState(false);
  const [detail, setDetail] = useState<"membership" | "relay" | null>(null);
  const [connectionTarget, setConnectionTarget] = useState<{peer: NetworkPeerSnapshot; route: NetworkSnapshot["routes"][number] | null; memberStatus: string} | null>(null);
  const [terminationTarget, setTerminationTarget] = useState<{peer: NetworkPeerSnapshot; routeKind: string} | null>(null);

  const fail = (error: unknown, fallback: string) => {
    setVersionMismatch(error instanceof ControlPlaneError && error.code === "INCOMPATIBLE_VERSION");
    setActionMessage(error instanceof Error ? error.message : fallback);
  };

  const createInvite = async () => {
    if (!client) return;
    setBusy(true);
    try {
      const response = await client.invite(undefined, openRouterPort, true);
      setInvite(response.inviteUri);
      setInviteOperation(response.operationId);
      setVersionMismatch(false);
      setActionMessage(response.kind !== "PROJECT"
        ? "Invitation created. Send it to the other computer; this one waits for it."
        : response.codeRemoteUrl
          ? `Invitation created. It names the Git remote ${response.codeRemoteName ?? ""} (${response.codeRemoteUrl}): that is where the other computer gets the code. Send it to the other computer; this one waits for it and asks you before it lets that computer in.`
          : `Invitation created, but it names no code remote (${codeRemoteReasonText(response.codeRemoteReason)}). The other computer cannot be accepted until the project has one: add a Git remote, then create a new invitation.`);
    } catch (error) {
      fail(error, "Invitation failed.");
    } finally { setBusy(false); }
  };
  const decide = async (nodeId: string, accept: boolean) => {
    if (!client) return;
    setBusy(true);
    try {
      await (accept ? client.acceptJoin(nodeId) : client.declineJoin(nodeId));
      setDecided((current) => [...current, nodeId]);
      setVersionMismatch(false);
      setActionMessage(accept ? "Accepted. That computer is now a member and is served the project's log." : "Declined. That computer was disconnected.");
    } catch (error) {
      fail(error, accept ? "The request could not be accepted." : "The request could not be declined.");
    } finally { setBusy(false); }
  };
  const joinRequests = (onboarding?.joinRequests ?? []).filter((request) => !decided.includes(request.nodeId));
  const submitAnswer = async () => {
    if (!client || !inviteOperation || !answerUri.trim()) return;
    setBusy(true);
    try {
      await client.answer(inviteOperation, answerUri.trim());
      setVersionMismatch(false);
      setActionMessage("Answer accepted. This computer is now reaching out to the other one.");
    } catch (error) {
      fail(error, "The answer link was not accepted.");
    } finally { setBusy(false); }
  };

  return (
    <div className="view-stack">
      <div className="view-intro">
        <div><p className="eyebrow">Project connectivity</p><h2>Network</h2><p>Physical peers, signed membership, and server-selected routes.</p></div>
        <div className="view-intro-actions">
          <span className="state-pair">Network <StatusBadge value={network.status}/></span>
          <span className="state-pair">Overlay <StatusBadge value={network.overlay.status}/></span>
          <span className="state-pair">Relay <StatusBadge value={network.relay.status}/></span>
          <button className="button button-secondary" type="button" onClick={() => setDetail("membership")}><Network size={15}/> Membership</button>
          <button className="button button-secondary" type="button" onClick={() => setDetail("relay")}><Link2 size={15}/> Relay detail</button>
        </div>
      </div>
      <section className="onboarding panel" aria-label="Link onboarding">
        <div className="onboarding-grid">
          <JoinPanel client={client}/>
          <div className="form-block"><span className="form-label">Invite another computer</span><p className="muted">The invitation names this project and the Git remote code is fetched from, and expires after a few minutes. When another computer opens it, this one asks you before it lets that computer in.</p><div className="inline-form"><button className="button button-secondary" type="button" disabled={busy || !client} onClick={() => void createInvite()}><Plus size={15}/> Create Invitation</button></div><label className="muted"><input type="checkbox" checked={openRouterPort} onChange={(event) => setOpenRouterPort(event.target.checked)}/> Ask my router to open a port (UPnP), so a computer on another network can connect. This changes a router setting until the session ends.</label></div>
        </div>
        {invite && <CopyField label="Invitation URI" value={invite}/>}
        {inviteOperation && <div className="form-block"><label className="form-label" htmlFor="answer-uri">Answer from the other computer</label><p className="muted">Only needed when the other computer is on a different network and does not connect on its own.</p><div className="inline-form"><input className="text-field" id="answer-uri" value={answerUri} onChange={(event) => setAnswerUri(event.target.value)} placeholder="Paste the answer link" /><button className="button button-secondary" type="button" disabled={busy || !client || !answerUri.trim()} onClick={() => void submitAnswer()}><Check size={15}/> Use Answer</button></div></div>}
        {joinRequests.length > 0 && <div className="form-block" role="group" aria-label="Computers waiting to join">
          {joinRequests.map((request) => <div className="inline-form" key={request.nodeId}>
            <span><span className="mono" title={request.nodeId}>{shortNode(request.nodeId)}</span> wants to join {projectName ?? "this project"}</span>
            <button className="button button-primary" type="button" disabled={busy || !client} aria-label={"Accept " + shortNode(request.nodeId)} onClick={() => void decide(request.nodeId, true)}><Check size={15}/> Accept</button>
            <button className="button button-secondary" type="button" disabled={busy || !client} aria-label={"Decline " + shortNode(request.nodeId)} onClick={() => void decide(request.nodeId, false)}><X size={15}/> Decline</button>
          </div>)}
        </div>}
        {actionMessage && <p className={cx("action-message", versionMismatch && "selection-error")} role={versionMismatch ? "alert" : "status"}>{actionMessage}</p>}
      </section>
                <section className="data-table-wrap panel">
            <table className="data-table"><thead><tr><th>Peer ID</th><th>Route</th><th>Membership State</th><th>Connection Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>{network.peers.length === 0 ? <TableEmpty colSpan={5} text="No physical peers are projected."/> : network.peers.map((peer) => {
                const route = network.routes.find((item) => item.destinationNodeId === peer.nodeId);
                const member = network.overlay.members.find((item) => item.nodeId === peer.nodeId);
                const reachable = peer.usable && peer.authenticated;
                const openDetails = () => setConnectionTarget({peer, route: route ?? null, memberStatus: member?.status || "UNKNOWN"});
                return <tr key={peer.nodeId + peer.sessionId} tabIndex={0} aria-label={`View connection details for ${peer.nodeId}`} onClick={openDetails} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); openDetails(); } }}><td className="mono">{peer.nodeId}</td><td className="mono table-cell-wrap">{route ? route.kind + (route.nextHop ? " · " + route.nextHop : "") : "—"}</td><td><StatusBadge value={member?.status || "UNKNOWN"}/></td><td><span className={cx("connection-status", reachable ? "is-connected" : "is-unreachable")}><span className="status-dot"/>{peer.liveness || (reachable ? "CONNECTED" : "UNREACHABLE")}</span></td><td className="table-action-cell"><button className="icon-button" type="button" aria-label={`Terminate connection to ${peer.nodeId}`} title="Terminate connection" onClick={(event) => { event.stopPropagation(); setTerminationTarget({peer, routeKind: route?.kind ?? "UNKNOWN"}); }}><Trash2 size={15}/></button></td></tr>;
              })}</tbody>
            </table>
          </section>
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
      <section className="view-intro" aria-labelledby="diagnostics-title">
        <div>
          <p className="eyebrow">Report <code>{diagnostics.reportId || "—"}</code> · {formatDiagnosticDate(diagnostics.timestampEpochMillis)}</p>
          <h2 id="diagnostics-title">Diagnostics</h2>
          <p>{hasIssues ? "Issues require attention." : "No issues require attention."}</p>
        </div>
        <StatusBadge value={overallStatus} label={formatDiagnosticValue(overallStatus)} large/>
      </section>
      <div className="stat-strip" aria-label="Diagnostic severity counts">
        <Stat label="Critical" value={diagnostics.criticalCount} tone={diagnostics.criticalCount > 0 ? "bad" : undefined}/>
        <Stat label="Errors" value={diagnostics.errorCount} tone={diagnostics.errorCount > 0 ? "bad" : undefined}/>
        <Stat label="Warnings" value={diagnostics.warningCount} tone={diagnostics.warningCount > 0 ? "warn" : undefined}/>
        <Stat label="Info" value={diagnostics.infoCount}/>
      </div>
      <section className="data-table-wrap panel">
        <table className="data-table"><thead><tr><th>Severity</th><th>Finding</th><th>Confidence</th><th>Component</th><th>Recommendation</th></tr></thead>
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
      <section className="panel diagnostics-modal-card" role="dialog" aria-modal="true" aria-labelledby="finding-detail-title" aria-describedby="finding-detail-explanation" onClick={(event) => event.stopPropagation()}>
        <div className="inspector-heading">
          <span>Finding Detail</span>
          <div className="diagnostics-modal-actions">
            <button className="icon-button" type="button" aria-label="Previous finding" disabled={index <= 0} onClick={() => onNavigate(-1)}><ChevronLeft size={18}/></button>
            <span className="diagnostics-modal-index" aria-live="polite">{index + 1} of {total}</span>
            <button className="icon-button" type="button" aria-label="Next finding" disabled={index >= total - 1} onClick={() => onNavigate(1)}><ChevronRight size={18}/></button>
            <button ref={closeButtonRef} className="icon-button" type="button" aria-label="Close finding detail" onClick={onClose}><X size={17}/></button>
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
  return <main className="connection-screen"><section className="connection-card"><img className="brand-logo brand-logo-large" src="/synesis-logo.png" alt="" aria-hidden="true"/><p className="eyebrow">Synesis local control plane</p><h1>Connecting to project state</h1><StatusBadge value={state}/><p>{message}</p><div className="loading-line" aria-hidden="true"/></section></main>;
}

function StatusBadge({value, large, label}: {value: string; large?: boolean; label?: string}) {
  return <span className={cx("status-badge", "status-" + statusTone(value), large && "status-large")}><span className="status-dot"/>{label ?? formatStatus(value)}</span>;
}

function OverviewSection({title, icon: Icon, action, children}: {title: string; icon: LucideIcon; action?: ReactNode; children: ReactNode}) {
  return <section className="panel overview-section"><div className="toolbar"><h2><Icon size={16} aria-hidden="true"/>{title}</h2>{action}</div>{children}</section>;
}

function OverviewStat({label, value}: {label: string; value: number}) {
  return <div className="overview-list-row"><span>{label}</span><strong>{value}</strong></div>;
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
      <section className="panel detail-modal-card" role="dialog" aria-modal="true" aria-labelledby="detail-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="inspector-heading"><span id="detail-modal-title">{title}</span><button ref={closeButtonRef} className="icon-button" type="button" aria-label={`Close ${title.toLowerCase()}`} onClick={onClose}><X size={16}/></button></div>
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
