export type ProjectView = "overview" | "agents" | "coordination" | "network" | "diagnostics";

export const projectTabs: Array<{id: ProjectView; label: string}> = [
  {id: "overview", label: "Overview"},
  {id: "agents", label: "Agents"},
  {id: "coordination", label: "Coordination"},
  {id: "network", label: "Network"},
  {id: "diagnostics", label: "Diagnostics"},
];

export type Route =
  | {kind: "projects"}
  | {kind: "project"; projectId: string; view: ProjectView};

export function parseRoute(path = typeof window === "undefined" ? "/projects" : window.location.pathname): Route {
  const parts = path.split("/").filter(Boolean);
  if (parts[0] !== "projects" || !parts[1]) return {kind: "projects"};
  const projectId = decodeURIComponent(parts[1]);
  const requestedView = parts[2] as ProjectView | undefined;
  const view = projectTabs.some((tab) => tab.id === requestedView) ? requestedView! : "overview";
  return {kind: "project", projectId, view};
}

export function routePath(route: Route) {
  return route.kind === "projects" ? "/projects" : "/projects/" + encodeURIComponent(route.projectId) + "/" + route.view;
}
