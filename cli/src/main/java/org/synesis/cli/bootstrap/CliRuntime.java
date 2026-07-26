package org.synesis.cli.bootstrap;

import java.nio.file.Path;

import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.cli.terminal.Terminal;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ConstraintApplicationService;
import org.synesis.workspace.application.GuardrailApplicationService;
import org.synesis.workspace.application.HookApplicationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderApplicationService;
import org.synesis.workspace.application.SyncApplicationService;

/**
 * Manual composition point for one CLI invocation.
 *
 * @param onboarding         Link onboarding façade
 * @param terminal           terminal boundary
 * @param readinessInspector local readiness inspector
 * @param projectService     project discovery and initialization service
 * @param constraintService  constraint service
 * @param guardrailService   guardrail service
 * @param hookService        hook service
 * @param syncService        synchronization service
 * @param providerService    provider lifecycle service
 * @since 1.0
 */
public record CliRuntime(Onboarding onboarding, Terminal terminal, ReadinessInspector readinessInspector,
                         ProjectApplicationService projectService, ConstraintApplicationService constraintService,
                         GuardrailApplicationService guardrailService, HookApplicationService hookService,
                         SyncApplicationService syncService, ProviderApplicationService providerService) {

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
                new HookApplicationService(), new SyncApplicationService(), new ProviderApplicationService());
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
     * @param providerService    provider lifecycle service
     */
    public CliRuntime {
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
     * Returns the project application service.
     *
     * @return project application service
     */
    @Override
    public ProjectApplicationService projectService() {
        return projectService;
    }

    /**
     * Returns the constraint application service.
     *
     * @return constraint application service
     */
    @Override
    public ConstraintApplicationService constraintService() {
        return constraintService;
    }

    /**
     * Returns the guardrail application service.
     *
     * @return guardrail application service
     */
    @Override
    public GuardrailApplicationService guardrailService() {
        return guardrailService;
    }

    /**
     * Returns the hook application service.
     *
     * @return hook application service
     */
    @Override
    public HookApplicationService hookService() {
        return hookService;
    }

    /**
     * Returns the synchronization application service.
     *
     * @return synchronization application service
     */
    @Override
    public SyncApplicationService syncService() {
        return syncService;
    }

    /**
     * Returns the provider lifecycle service.
     *
     * @return provider service
     */
    @Override
    public ProviderApplicationService providerService() {
        return providerService;
    }

}
