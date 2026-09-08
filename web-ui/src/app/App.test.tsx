import { render, screen } from "@testing-library/react";
import { AgentsView, ProjectsView } from "./App";
import type { Snapshot } from "../api/controlPlane";

const emptySnapshot = { project: null, runtime: { status: "RUNNING" } } as Snapshot;

describe("truthful product states", () => {
  it("shows an empty projects state instead of demo content", () => {
    render(<ProjectsView snapshot={emptySnapshot} />);
    expect(screen.getByText("No project initialized")).toBeInTheDocument();
    expect(screen.queryByText("Project Alpha")).not.toBeInTheDocument();
  });

  it("supports an empty agents state", () => {
    render(<AgentsView agents={[]} />);
    expect(screen.getByText("No agents yet")).toBeInTheDocument();
  });
});
