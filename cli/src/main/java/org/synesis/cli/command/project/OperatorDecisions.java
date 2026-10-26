package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.link.identity.OperatorKeyException;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.ProjectPolicyService;
import org.synesis.workspace.application.project.ProjectPolicyService.AdoptionChoice;
import org.synesis.workspace.application.project.ProjectPolicyService.Decided;

/**
 * What every command that writes an owner decision shares (ADR-0104, Part A): asking a person for the operator key's
 * passphrase at the terminal, signing, and printing what was decided. These commands run in a person's own terminal;
 * none can be run by the MCP server or the daemon, and an agent's command has no terminal to type a passphrase at.
 */
final class OperatorDecisions {

    private static final int MINIMUM_PASSPHRASE = 8;

    private OperatorDecisions() {
    }

    /**
     * Signs the decisions, asking the person for the operator key's passphrase.
     *
     * @param runtime   CLI runtime
     * @param location  the project
     * @param decisions what to decide
     * @param choice    which copy of the project file a first decision adopts when they differ
     * @return what was written
     * @throws ProjectApplicationException when the decision is refused
     */
    static Decided sign(CliRuntime runtime, ProjectLocation location, Map<PolicyName, String> decisions, Optional<AdoptionChoice> choice)
            throws ProjectApplicationException {
        return ProjectPolicyService.decide(location, newKey -> askPassphrase(runtime, newKey), decisions, choice);
    }

    /**
     * Prints what a signed decision did, after the command printed the policy it changed.
     *
     * @param runtime CLI runtime
     * @param decided what was written
     */
    static void printDecided(CliRuntime runtime, Decided decided) {
        decided.adopted().ifPresent(adopted -> {
            runtime.terminal().stdout("POLICY_BASELINE=SIGNED");
            if (adopted.validation() != null) {
                runtime.terminal().stdout("POLICY_ADOPTED=validation");
            }
            if (adopted.alsoInLane()) {
                runtime.terminal().stdout("POLICY_ADOPTED=validation.alsoInLane");
            }
            if (adopted.reviewMandatory()) {
                runtime.terminal().stdout("POLICY_ADOPTED=review");
            }
        });
        runtime.terminal().stdout("POLICY_REVISION=" + decided.revision());
        if (decided.written() > 0) {
            runtime.terminal().stdout("DECISION_ENTRY=" + decided.entry());
        }
    }

    /**
     * Prints a refusal in the shape of ADR-0103 and returns the exit code.
     *
     * @param runtime CLI runtime
     * @param failure the refusal
     * @return the exit code
     */
    static int refuse(CliRuntime runtime, ProjectApplicationException failure) {
        if (ErrorShape.explains(failure.code())) {
            ErrorShape.print(runtime.terminal()::stderr, failure.code());
            runtime.terminal().stderr("REASON=" + failure.getMessage());
        } else {
            ProjectValidationCommand.printFailure(runtime, failure);
        }
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    private static char[] askPassphrase(CliRuntime runtime, boolean newKey) throws IOException {
        // The explanation is part of the prompt, so it appears only where a person is there to read it.
        char[] first = runtime.terminal().readSecret(newKey
                ? "This project has no operator key yet. It signs your decisions about validation, review and the sandbox, and is kept encrypted under a passphrase you type here.\nNew operator key passphrase: "
                : "Operator key passphrase: ");
        if (first == null || !newKey) {
            return first;
        }
        char[] again = runtime.terminal().readSecret("Repeat the passphrase: ");
        try {
            if (first.length < MINIMUM_PASSPHRASE || again == null || !Arrays.equals(first, again)) {
                Arrays.fill(first, '\0');
                throw new OperatorKeyException(first.length < MINIMUM_PASSPHRASE ? "OPERATOR_PASSPHRASE_TOO_SHORT" : "OPERATOR_PASSPHRASE_MISMATCH");
            }
            return first;
        } finally {
            if (again != null) {
                Arrays.fill(again, '\0');
            }
        }
    }
}
