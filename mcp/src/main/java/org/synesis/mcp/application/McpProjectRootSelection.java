package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpJsonRpcResponses.createResultResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;

/**
 * Owns which project root one MCP connection operates on.
 *
 * <p>The root starts as the launcher-supplied path and may be replaced from the client's
 * {@code initialize} params or a roots notification. A rejected or ambiguous selection latches a
 * fail-closed authority diagnostic; once latched, resolution keeps returning the current root so a
 * later candidate can never move the connection to a different project. These fields are assigned
 * only here: the handler and the other connection groups read the result and never write it, which
 * leaves one place where a root can be adopted.</p>
 */
final class McpProjectRootSelection {

    /**
     * Project root supplied by the launcher before MCP roots are resolved.
     */
    private final Path initialProjectRoot;
    /**
     * Whether the launcher explicitly pinned the initial project root.
     */
    private final boolean projectRootPinned;
    /**
     * Project root selected from the initialized request or roots notification.
     */
    private Path activeProjectRoot;
    /** Whether this connection resolved one initialized project root. */
    private boolean projectRootResolved;
    /** Whether the selected project was recorded in the installation-local discovery index. */
    private boolean projectObserved;
    /**
     * Fail-closed root-authority diagnostic set before provider admission.
     */
    private String projectRootAuthorityFailure;

    /**
     * Creates the selection state for one connection.
     *
     * @param initialProjectRoot launcher-supplied project root, also the initial active root
     * @param projectRootPinned  whether {@code initialProjectRoot} came from an explicit launcher setting
     */
    McpProjectRootSelection(Path initialProjectRoot, boolean projectRootPinned) {
        this.initialProjectRoot = initialProjectRoot;
        this.projectRootPinned = projectRootPinned;
        this.activeProjectRoot = initialProjectRoot;
    }

    /**
     * Returns the currently resolved active control project root path.
     *
     * @return active control project root path
     */
    Path activeProjectRoot() {
        return activeProjectRoot;
    }

    /**
     * Returns whether this connection resolved one project root from the launcher or the client.
     *
     * @return {@code true} once a root has been adopted
     */
    boolean projectRootResolved() {
        return projectRootResolved;
    }

    /**
     * Returns the latched fail-closed root-authority diagnostic.
     *
     * @return the diagnostic, or {@code null} when root selection has not been rejected
     */
    String projectRootAuthorityFailure() {
        return projectRootAuthorityFailure;
    }

    /**
     * Adopts a resolved root as the connection's project and records it in the discovery index.
     *
     * @param resolved root returned by {@link #resolveProjectRootFromCandidates(List)}
     */
    void adopt(Path resolved) {
        this.activeProjectRoot = resolved;
        this.projectRootResolved = true;
        observeProjectRoot();
    }

    /**
     * Applies an early roots notification only while this connection remains unbound.
     *
     * @param paramsObj notification params, possibly {@code null}
     * @param isSessionBound whether this connection is already bound to a session
     * @return always {@code null}; notifications get no response
     */
    @SuppressWarnings("unchecked")
    String handleRootsListChanged(Object paramsObj, boolean isSessionBound) {
        if (!isSessionBound && paramsObj instanceof Map<?, ?> pMap) {
            List<Path> candidates = McpRootCandidates.extractCandidateRoots((Map<String, Object>) pMap);
            Path resolved = resolveProjectRootFromCandidates(candidates);
            if (resolved != null) {
                adopt(resolved);
            }
        }
        return null;
    }

    /**
     * Returns the one project root currently selected for this connection.
     *
     * @param id JSON-RPC request identifier
     * @return the roots/list result response
     */
    String handleRootsList(Object id) {
        if (!projectRootResolved) {
            return createResultResponse(id, Map.of("roots", List.of()));
        }
        Map<String, Object> rootItem = new LinkedHashMap<>();
        rootItem.put("uri", activeProjectRoot.toUri().toString());
        rootItem.put("name", activeProjectRoot.getFileName() != null ? activeProjectRoot.getFileName().toString() : "root");

        Map<String, Object> result = Map.of("roots", List.of(rootItem));
        return createResultResponse(id, result);
    }

    /**
     * Records the selected project without making discovery-index availability a startup gate.
     */
    private void observeProjectRoot() {
        if (!projectRootResolved || projectObserved) {
            return;
        }
        projectObserved = true;
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            new KnownProjectRegistry().observe(location);
        } catch (KnownProjectRegistry.RegistryException failure) {
            System.err.println("SYNESIS_MCP_PROJECT_DISCOVERY_ERROR=" + failure.code());
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            System.err.println("SYNESIS_MCP_PROJECT_DISCOVERY_ERROR=" + failure.code());
        } catch (IOException failure) {
            System.err.println("SYNESIS_MCP_PROJECT_DISCOVERY_ERROR=IO");
        } catch (Exception failure) {
            System.err.println("SYNESIS_MCP_PROJECT_DISCOVERY_ERROR=" + failure.getClass().getSimpleName());
        }
    }

    /**
     * Resolves the single initialized Synesis control project root from candidate paths.
     *
     * @param candidates candidate workspace paths
     * @return resolved control project root, or {@code null} if none or ambiguous
     */
    Path resolveProjectRootFromCandidates(List<Path> candidates) {
        if (projectRootAuthorityFailure != null) {
            return activeProjectRoot;
        }
        String userHome = System.getProperty("user.home");
        Path homePath = (userHome != null && !userHome.isBlank()) ? Path.of(userHome).toAbsolutePath().normalize() : null;
        if (projectRootPinned) {
            McpProjectRootAuthority.Selection selection = McpProjectRootAuthority.select(initialProjectRoot, candidates, homePath);
            if (selection.rejected()) {
                projectRootAuthorityFailure = selection.rejection();
                System.err.println("SYNESIS_DIAGNOSTIC=" + selection.rejection());
                return initialProjectRoot;
            }
            return selection.root();
        }

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        List<Path> initializedRoots = new ArrayList<>();

        for (Path candidate : candidates) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (McpProjectRootAuthority.isAssignedWorkspace(normalized)) {
                    projectRootAuthorityFailure = "PROJECT_ROOT_ASSIGNED_WORKTREE_REJECTED";
                    System.err.println("SYNESIS_DIAGNOSTIC=" + projectRootAuthorityFailure);
                    return activeProjectRoot;
                }
                if (normalized.equals(homePath)) {
                    continue; // Reject user home directory as control project root
                }
                Path mainCheckout = McpProjectRootAuthority.resolveMainCheckout(normalized);
                if (mainCheckout != null && !initializedRoots.contains(mainCheckout)) {
                    initializedRoots.add(mainCheckout);
                }
            } catch (Exception ignored) {
                // An unreadable candidate is skipped; the others decide the root.
            }
        }

        if (initializedRoots.size() == 1) {
            projectRootAuthorityFailure = null;
            return initializedRoots.getFirst();
        }

        if (initializedRoots.size() > 1) {
            System.err.println("SYNESIS_DIAGNOSTIC=PROJECT_ROOT_AMBIGUOUS count=" + initializedRoots.size());
            projectRootAuthorityFailure = "PROJECT_ROOT_AMBIGUOUS";
            return activeProjectRoot;
        }

        return null;
    }
}
