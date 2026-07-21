package org.synesis.cli.bootstrap;

import java.nio.file.Path;

import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.cli.terminal.Terminal;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.transport.Onboarding;
import org.synesis.workspace.application.ConstraintApplicationService;
import org.synesis.workspace.application.GuardrailApplicationService;
import org.synesis.workspace.application.HookApplicationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.SyncApplicationService;

/**
 * Manual composition point for one CLI invocation.
 *
 * @param onboarding Link onboarding façade
 * @param terminal terminal boundary
 * @param readinessInspector local readiness inspector
 * @param projectService project discovery and initialization service
 * @param constraintService constraint service
 * @param guardrailService guardrail service
 * @param hookService hook service
 * @param syncService synchronization service
 * @since 1.0
 */
public record CliRuntime(Onboarding onboarding, Terminal terminal, ReadinessInspector readinessInspector,
                         ProjectApplicationService projectService, ConstraintApplicationService constraintService,
                         GuardrailApplicationService guardrailService, HookApplicationService hookService,
                         SyncApplicationService syncService) {
    /**
     * Creates an injectable runtime for command tests and process execution.
     *
     * @param onboarding         Link façade
     * @param terminal           terminal boundary
     * @param readinessInspector local readiness inspector
     */
    public CliRuntime(Onboarding onboarding, Terminal terminal, ReadinessInspector readinessInspector) {
        this(onboarding, terminal, readinessInspector, new ProjectApplicationService(),
                new ConstraintApplicationService(), new GuardrailApplicationService(),
                new HookApplicationService(), new SyncApplicationService());
    }

    /**
     * Creates a fully composed runtime.
     *
     * @param onboarding         Link onboarding façade
     * @param terminal           terminal boundary
     * @param readinessInspector local readiness inspector
     * @param projectService     project discovery and initialization service
     * @param constraintService  constraint service
     * @param guardrailService   guardrail service
     * @param hookService        hook service
     * @param syncService        synchronization service
     */
    public CliRuntime {
    }

    /**
     * Creates the production runtime using the default profile.
     *
     * @return manually composed runtime
     */
    public static CliRuntime defaults() {
        return defaults(new ConsoleTerminal());
    }

    /**
     * Creates a runtime with a supplied terminal and default Link profile.
     *
     * @param terminal terminal boundary
     * @return manually composed runtime
     */
    public static CliRuntime defaults(Terminal terminal) {
        StatusRenderer renderer = new StatusRenderer(terminal);
        Path profile = IdentityBootstrap.defaultDirectory();
        return new CliRuntime(new Onboarding(profile, renderer), terminal, new ReadinessInspector(profile));
    }

    /**
     * Returns the Link-owned onboarding façade.
     *
     * @return onboarding façade
     */
    @Override
    public Onboarding onboarding() {
        return onboarding;
    }

    /**
     * Returns the terminal boundary.
     *
     * @return terminal
     */
    @Override
    public Terminal terminal() {
        return terminal;
    }

    /**
     * Returns the read-only readiness inspector.
     *
     * @return readiness inspector
     */
    @Override
    public ReadinessInspector readinessInspector() {
        return readinessInspector;
    }

    /**
     * Returns the project application service.
     * @return project application service
     */
    @Override
    public ProjectApplicationService projectService() {
        return projectService;
    }

    /**
     * Returns the constraint application service.
     * @return constraint application service
     */
    @Override
    public ConstraintApplicationService constraintService() {
        return constraintService;
    }

    /**
     * Returns the guardrail application service.
     * @return guardrail application service
     */
    @Override
    public GuardrailApplicationService guardrailService() {
        return guardrailService;
    }

    /**
     * Returns the hook application service.
     * @return hook application service
     */
    @Override
    public HookApplicationService hookService() {
        return hookService;
    }

    /**
     * Returns the synchronization application service.
     * @return synchronization application service
     */
    @Override
    public SyncApplicationService syncService() {
        return syncService;
    }

    /**
     * Resolves a project profile from an explicit override or discovered project.
     *
     * @param project explicit project path, or {@code null} for upward discovery
     * @param profile explicit advanced profile override, or {@code null}
     * @return normalized profile path
     * @throws ProjectApplicationService.ProjectApplicationException if discovery fails
     */
    public Path resolveProfile(Path project, Path profile)
            throws ProjectApplicationService.ProjectApplicationException {
        if (profile != null) return profile.toAbsolutePath().normalize();
        ProjectApplicationService.ProjectLocation location = project == null
                ? projectService.locate(Path.of(".")) : projectService.require(project);
        return projectService.profile(location);
    }
}
