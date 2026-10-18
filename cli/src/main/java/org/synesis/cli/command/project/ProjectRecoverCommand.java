package org.synesis.cli.command.project;

import java.util.Optional;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.membership.RecoveryKey;
import org.synesis.coordination.exception.CoordinationArgumentException;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Command;

/**
 * Makes this computer the owner's with the project's recovery key (ADR-0098 step 6, designer item 12): the person types
 * the 24 words on standard input, so they never reach a shell's history, and the command writes {@code OWNER_CHANGED}
 * signed by the recovery key, naming this computer's node key as the owner key and a new recovery key, whose words it
 * prints once. The old words authorise nothing more.
 */
@Command(name = "recover", description = "Become the project's owner with the 24 recovery words, read from standard input.", mixinStandardHelpOptions = true)
public final class ProjectRecoverCommand extends ProjectRoleCommand {

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectRecoverCommand(CliRuntime runtime) {
        super(runtime, DAEMON);
    }

    @Override
    void apply(Role role) throws Exception {
        runtime.terminal().stdout("RECOVERY_WORDS_PROMPT=Type the 24 recovery words on one line, then press Enter.");
        String typed = runtime.terminal().readLine();
        RecoveryKey recovery;
        try {
            recovery = RecoveryKey.fromWords(typed == null ? "" : typed);
        } catch (CoordinationArgumentException unreadable) {
            throw new Refusal(unreadable.getMessage().split(":")[0]);
        }
        NodeIdentity identity = role.location().loadOrCreateIdentity();
        RecoveryKey next = RecoveryKey.generate();
        new MembershipService(role.location().openCoordinationStore(), identity).recover(recovery, Optional.of(next));
        runtime.terminal().stdout("OWNER_NODE_ID=" + identity.nodeId());
        runtime.terminal().stdout("RECOVERY_WORDS=" + String.join(" ", next.words()));
        runtime.terminal().stdout("RECOVERY_WORDS_NOTE=Write these new 24 words down now; the old ones authorise nothing more.");
    }

    @Override
    boolean needsRuntimeRestart() {
        return false;
    }
}
