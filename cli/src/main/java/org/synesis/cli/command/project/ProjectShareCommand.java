package org.synesis.cli.command.project;

import java.util.Optional;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.SharedSettingsMigration;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.membership.RecoveryKey;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shares the project with this computer as its owner, so that other computers can join it (ADR-0098 step 6): writes
 * {@code PROJECT_SHARED} with this computer's node key as the owner key and a new recovery key, whose 24 words are
 * printed once and stored nowhere. Run again on the owner's computer of a project that has no recovery key, or with
 * {@code --new-recovery-key}, it names a new one and prints its words.
 */
@Command(name = "share", description = "Share the project with this computer as its owner, and show the recovery words once.", mixinStandardHelpOptions = true)
public final class ProjectShareCommand extends ProjectRoleCommand {

    @Option(names = "--code-remote", description = "Name of a Git remote of this clone to keep as a mirror of the code. Code moves over Link without one.")
    private String codeRemote;

    @Option(names = "--new-recovery-key", description = "Replace the project's recovery key with a new one and show its words.")
    private boolean newRecoveryKey;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectShareCommand(CliRuntime runtime) {
        this(runtime, DAEMON);
    }

    ProjectShareCommand(CliRuntime runtime, RuntimeStopper stopper) {
        super(runtime, stopper);
    }

    @Override
    void apply(Role role) throws Exception {
        if (role.settings().isPresent()) {
            // A computer that follows another one is refused.
            role.leader();
        }
        if (codeRemote != null) {
            requireRemote(role, codeRemote);
        }
        NodeIdentity identity = role.location().loadOrCreateIdentity();
        PredictionEventStore store = role.location().openCoordinationStore();
        // A step-5 owner's members move into the log first (designer item 14).
        SharedSettingsMigration.migrate(role.coordination(), store, identity);
        MembershipService membership = new MembershipService(store, identity);
        MembershipProjection before = role.location().openCoordinationStore().membershipProjection();
        if (before.shared() && !before.owner().map(identity.nodeId()::equals).orElse(false)) {
            throw new Refusal("NOT_THE_OWNER");
        }
        RecoveryKey recovery = RecoveryKey.generate();
        boolean shown;
        if (!before.shared()) {
            shown = membership.share(Optional.of(recovery));
        } else if (newRecoveryKey || before.recoveryPublicKey().length == 0) {
            membership.replaceRecoveryKey(recovery);
            shown = true;
        } else {
            shown = false;
        }
        String remote = codeRemote != null ? codeRemote : role.settings().map(ReplicationSettings::codeRemote).orElse(null);
        role.write(ReplicationSettings.sharedProject(remote));
        runtime.terminal().stdout("ROLE=shared");
        runtime.terminal().stdout("NODE_ID=" + identity.nodeId());
        runtime.terminal().stdout("OWNER_NODE_ID=" + identity.nodeId());
        runtime.terminal().stdout("CODE_REMOTE=" + (remote == null ? "" : remote));
        runtime.terminal().stdout("MEMBERS=" + role.location().openCoordinationStore().membershipProjection().members().size());
        if (shown) {
            runtime.terminal().stdout("RECOVERY_WORDS=" + String.join(" ", recovery.words()));
            runtime.terminal().stdout("RECOVERY_WORDS_NOTE=Write these 24 words down now. They are shown once and stored nowhere; "
                    + "with them any member computer can become the owner's ('synesis project recover').");
        } else {
            runtime.terminal().stdout("RECOVERY_KEY=ALREADY_SET");
        }
    }

    @Override
    boolean needsRuntimeRestart() {
        return true;
    }
}
