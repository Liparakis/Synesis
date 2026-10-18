package org.synesis.cli.command.project;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicy;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Says who may let a joining computer in besides the owner (ADR-0098 step 6, owner's decision 13): {@code OWNER_ONLY},
 * the default, or {@code ANY_MEMBER}, under which any member may accept a join while the owner is away. A decline
 * stays the owner's. Only the owner's computer can set it.
 */
@Command(name = "join-policy", description = "Set who may accept a joining computer: OWNER_ONLY or ANY_MEMBER (owner only).", mixinStandardHelpOptions = true)
public final class ProjectJoinPolicyCommand extends ProjectRoleCommand {

    @Parameters(index = "0", arity = "1", description = "OWNER_ONLY or ANY_MEMBER.")
    private String policy;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectJoinPolicyCommand(CliRuntime runtime) {
        super(runtime, DAEMON);
    }

    @Override
    void apply(Role role) throws Exception {
        JoinPolicy wanted;
        try {
            wanted = JoinPolicy.valueOf(policy);
        } catch (IllegalArgumentException unknown) {
            throw new Refusal("JOIN_POLICY_UNKNOWN");
        }
        new MembershipService(role.location().openCoordinationStore(), role.location().loadOrCreateIdentity()).setPolicy(wanted);
        runtime.terminal().stdout("JOIN_POLICY=" + wanted);
    }

    @Override
    boolean needsRuntimeRestart() {
        return false;
    }
}
