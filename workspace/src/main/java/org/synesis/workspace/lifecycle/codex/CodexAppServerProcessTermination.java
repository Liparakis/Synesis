package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.synesis.workspace.lifecycle.codex.CodexAppServerLifecycleService.AppServerProcess;
import org.synesis.workspace.lifecycle.codex.CodexAppServerLifecycleService.ProcessLauncher;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope;
import org.synesis.workspace.lifecycle.codex.state.CodexLifecycleStateStore;

/**
 * Process-tree termination evidence for an App Server attachment.
 *
 * <p>Decides how a tree is proven dead: through the managed supervisor's death evidence when the
 * process has one, otherwise through the ownership-verified {@link ProcessTreeTerminator}. Every
 * collaborator is passed in, so nothing here reads or writes lifecycle state; the caller keeps the
 * choice of lock and ordering. Kept apart from {@link CodexAppServerLifecycleService} so the
 * fail-closed termination rules can be read without the state machine.
 */
final class CodexAppServerProcessTermination {

    private CodexAppServerProcessTermination() {
    }

    static boolean ownedTermination(ProcessTreeTerminator.Outcome outcome) {
        return outcome == ProcessTreeTerminator.Outcome.CLEAN_GRACEFUL || outcome == ProcessTreeTerminator.Outcome.FORCED || outcome == ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED;
    }

    /** Rejects managed-tree death evidence that does not prove the requested generation stopped. */
    static void requireManagedTreeDeathEvidence(long attachmentGeneration, ManagedProcessTreeSupervisor.DeathEvidence evidence) throws IOException {
        if (evidence == null || evidence.generation() != attachmentGeneration) {
            throw new IOException("managed_process_tree_death_evidence_mismatch");
        }
    }

    static ProcessTreeTerminator.Result terminateProcessTree(ProcessLauncher launcher,
            LifecycleControlRequestEnvelope.AuthorityContext authority,
            ProcessTreeTerminator terminator,
            AppServerProcess process,
            long attachmentGeneration,
            Duration grace,
            Instant deadline,
            CodexLifecycleStateStore.Checkpoint checkpoint) throws IOException {
        if (process != null && process.supervisor() != null) {
            ManagedProcessTreeSupervisor.DeathEvidence evidence = process.supervisor()
                    .teardownAndProveEmptyWithEvidence(process.process(), attachmentGeneration, process.executable(), process.commandIdentity(), process.startEpochMillis());
            launcher.managedTreeStopped(authority, attachmentGeneration, evidence);
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(Math.max(1L, process.process().pid()),
                    process.executable(),
                    process.commandIdentity(),
                    process.startEpochMillis(),
                    attachmentGeneration);
            return new ProcessTreeTerminator.Result(ProcessTreeTerminator.Outcome.FORCED, attachmentGeneration, identity.pid(), List.of(), List.of(), true, "managed_job_empty");
        }
        if (checkpoint.rootPid() <= 0) {
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(1L, "unknown", "unknown", Math.max(1L, checkpoint.rootStartEpochMillis()), attachmentGeneration);
            return new ProcessTreeTerminator.Result(ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED, attachmentGeneration, identity.pid(), List.of(), List.of(), false, "root_already_exited");
        }
        ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(checkpoint.rootPid(),
                checkpoint.rootExecutable(),
                checkpoint.rootCommandIdentity(),
                checkpoint.rootStartEpochMillis(),
                attachmentGeneration);
        return terminator.terminate(identity, attachmentGeneration, grace, deadline);
    }
}
