package org.synesis.cli.command;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The one shape of a setup refusal (ADR-0103, decision 3): {@code ERROR=<CODE>}, {@code MESSAGE=<what happened>} and
 * {@code FIX=<a command or step>}. The shape is added beside the keys a command already printed, so a script that reads
 * {@code REASON=} or a {@code *_RESULT=} line keeps working. A command names a code and, for a refusal whose
 * wording depends on its input, gives its own message and fix; every other code is explained here, in one place.
 */
public final class ErrorShape {

    private static final String UNLISTED_MESSAGE = "The command could not complete.";
    private static final String UNLISTED_FIX = "run 'synesis doctor', and repeat the command if it reports nothing";

    private record Advice(String message, String fix) {
    }

    private static Map.Entry<String, Advice> advice(String code, String message, String fix) {
        return Map.entry(code, new Advice(message, fix));
    }

    private static final Map<String, Advice> KNOWN = Map.ofEntries(
            advice("PROJECT_NOT_CONFIGURED", "This folder is not a Synesis project.", "run 'synesis init' in the project's folder, or pass --project <folder>"),
            advice("NOT_FOUND", "This folder is not a Synesis project.", "run 'synesis init' in the project's folder, or pass --project <folder>"),
            advice("PROJECT_INVALID", "The project folder given is not usable.", "pass --project <an existing project folder>"),
            advice("MALFORMED", "The project's metadata file is damaged.", "run 'synesis doctor --project <folder>' to see what is wrong, or restore the project's .synesis/project.json from a copy"),
            advice("PROJECT_ACCESS_FAILED", "The project could not be read.", "fix the file or permission REASON names, then repeat the command"),
            advice("METADATA_ROOT_UNAVAILABLE", "Synesis's private storage for this project is unavailable or unsafe.", "run 'synesis doctor' and check that your user can write to the folder it names"),
            advice("GIT_REQUIRED", "synesis init needs a Git repository and this folder is not one.", "run 'git init' in this folder, or run 'synesis init --git-init'"),
            advice("GIT_INIT_FAILED", "Git could not create a repository in this folder.", "check that Git is installed and the folder is writable, then run 'git init' yourself and 'synesis init' again"),
            advice("OPTIONS_CONFLICT", "Two options of this command ask for opposite things.", "give only one of them"),
            advice("AGENT_SETUP_FAILED", "Finding or connecting the agents failed.", "run 'synesis provider install claude' or 'synesis provider install codex' to connect one"),
            advice("GIT_HEAD_UNAVAILABLE", "The project's Git repository has no commit to build on.", "make a first commit in the repository, then repeat the command"),
            advice("CONFLICT", "The project is partly set up, or its state contradicts itself.", "run 'synesis doctor' and follow what it reports"),
            advice("PROJECT_RUNTIME_UNAVAILABLE", "The project's runtime is not running and could not be started.", "run 'synesis doctor --fix' to start it, or see REASON if that fails"),
            advice("DAEMON_BUILD_MISMATCH", "The Synesis daemon that is running is another build than this command.", "stop that daemon, then repeat; the next command starts one of this build"),
            advice("AGENT_NOT_INSTALLED", "The agent is not on this computer's PATH.", "install the agent or add its folder to the PATH, then run 'synesis init --agent <id>' again"),
            advice("DOCTOR_FAILED", "The diagnostics could not run.", "check that --project names a readable folder, then repeat 'synesis doctor'"),
            advice("INTERRUPTED","The command was interrupted before it finished.", "repeat the command"),

            advice("UNKNOWN_PROVIDER", "No provider has that name.", "run 'synesis provider list' for the providers you can use"),
            advice("PROFILE_MISSING", "The project's profile folder is missing.", "run 'synesis init' in the project's folder"),
            advice("LAUNCHER_MISSING", "The Synesis launcher the provider's hooks run was not found.", "reinstall Synesis, or put its bin folder on your PATH"),
            advice("MANUAL_INSTALL_FAILED", "The Synesis skill could not be written into the provider's user folder.", "check that your user can write to the folder MANUAL_REASON names, then repeat"),
            advice("OBSOLETE_PROVIDER_STATE", "The provider's saved state comes from a Synesis that cannot be read.", "run 'synesis migrate --prepare', or remove the provider with 'synesis provider uninstall <provider>' and install it again"),
            advice("INVALID_CONFIG", "The provider's configuration file cannot be read.", "fix or move the provider's configuration file named in 'synesis provider status <provider>', then repeat"),
            advice("PROVIDER_CONFIGURATION_CONFLICT", "The provider's configuration already holds a Synesis entry Synesis did not write.", "remove that entry by hand, then repeat the command"),
            advice("INSTALL_FAILED", "The provider integration could not be installed.", "run 'synesis doctor' and repeat 'synesis provider install <provider>'"),
            advice("UNINSTALL_FAILED", "The provider integration could not be removed.", "run 'synesis doctor' and repeat 'synesis provider uninstall <provider>'"),
            advice("GLOBAL_MCP_UNSUPPORTED", "This provider has no shared user-level MCP entry to remove.", "run 'synesis provider uninstall <provider>' without --global"),
            advice("GLOBAL_MCP_UNINSTALL_FAILED", "The shared user-level MCP entry could not be removed.", "edit the provider's user configuration file by hand and remove the 'synesis' entry"),
            advice("CONFLICTING_UNINSTALL_SCOPE", "--global removes only the shared user-level entry, so it cannot be combined with --project.", "give either --global or --project"),
            advice("BROKEN", "The provider integration is broken.", "run 'synesis provider install <provider>' to rewrite its managed files"),
            advice("PROVIDER_BROKEN", "The provider integration is broken.", "run 'synesis provider install <provider>' to rewrite its managed files"),
            advice("PROVIDER_DEGRADED", "The provider integration is installed but not healthy.", "run 'synesis provider install <provider>' to rewrite its managed files"),
            advice("MCP_CATALOG_MISMATCH", "The provider was installed by a Synesis whose tools differ from this one.", "run 'synesis provider install <provider>'"),
            advice("MANAGED_GUIDANCE_STALE", "The skill the provider reads is older than this Synesis.", "run 'synesis provider install <provider>'"),
            advice("CONFLICTING_MODES", "Only one of --dry-run, --prepare, --show-plan and --execute can be given.", "repeat the command with one of them"),
            advice("PROVIDER_MIGRATION_REVIEW_REQUIRED", "Provider configuration could not be migrated safely.", "run 'synesis provider migrate --dry-run' and read each provider it flags"),
            advice("PROVIDER_MIGRATION_STALE", "The prepared migration plan no longer matches the provider files.", "prepare a new plan with 'synesis provider migrate --prepare'"),

            advice("INVITE_INVALID", "The invitation is not valid, was changed, or has expired.", "ask the other computer to run 'synesis link invite' again and use its new link"),
            advice("ANSWER_INVALID", "The answer link is not valid, was already used, or has expired.", "run 'synesis link join' again on the joining computer and paste its new answer link"),
            advice("INCOMPATIBLE_VERSION", "The other computer runs a Synesis that cannot talk to this one.", "install the same Synesis version on both computers"),
            advice("INVALID_NODE_ID", "That is not a Synesis node ID.", "copy it from 'synesis link requests' here, or 'synesis identity show' on the other computer"),
            advice("PROJECT_FOLDER_NOT_EMPTY", "The folder given to --into already holds files.", "pass --into <an empty or new folder>"),
            advice("PROJECT_NOT_ON_THIS_COMPUTER", "This computer does not have that project yet.", "repeat 'synesis link join' with --into <an empty folder>"),
            advice("PROJECT_FOLDER_INVALID", "The project folder given cannot be used for this invitation.", "pass --project or --into <a folder of this project, or an empty one>"),
            advice("PROJECT_FOLDER_NOT_THE_PROJECT", "The folder given holds another project.", "pass the folder of the invited project, or --into <an empty folder>"),
            advice("PROJECT_IDENTITY_MISMATCH", "The folder given is not the project the invitation names.", "pass the folder of the invited project, or --into <an empty folder>"),
            advice("INVITER_IS_THIS_COMPUTER", "This computer made the invitation.", "send the link to the other computer and run 'synesis link join' there"),
            advice("FOLLOWING_ANOTHER_LEADER", "This project already follows another computer.", "run 'synesis project follow' only on the computer that should follow"),
            advice("ALREADY_A_LEADER", "This project is already the leader other computers follow.", "ask the other computer to join with 'synesis link join'"),
            advice("LOG_NOT_EMPTY", "This project already has its own history, so it cannot follow another computer's.", "join into an empty folder with --into <folder>"),
            advice("OPERATION_NOT_FOUND", "The runtime no longer knows that invitation.", "run 'synesis link invite' again"),
            advice("JOIN_REQUEST_UNKNOWN", "No computer with that node ID is waiting to join.", "run 'synesis link requests' for the computers that are"),
            advice("OPERATION_LIMIT", "Too many invitations are waiting.", "wait for one to end, or let it expire, then repeat"),
            advice("NO_USABLE_CANDIDATE", "No network route to the other computer was found.", "check that both computers are online and that the firewall rule exists ('synesis doctor'), or paste the answer link with 'synesis link answer'"),
            advice("HOST_TIMEOUT", "The other computer did not answer in time.", "check it is online and repeat the command"),
            advice("CONNECTION_FAILED", "The connection to the other computer failed.", "run 'synesis doctor' on both computers and repeat the command"),
            advice("EXPIRED", "The invitation expired before the other computer opened it.", "run 'synesis link invite' again"),
            advice("DECLINED", "The other computer declined.", "ask its owner to accept with 'synesis link accept'"),
            advice("CANCELLED", "The invitation was cancelled.", "run 'synesis link invite' again"),
            advice("SESSION_ENDED", "The other computer connected, but the session ended at once.", "run 'synesis doctor' on both computers and repeat the command"),
            advice("OPERATION_UNKNOWN", "The project's runtime no longer knows this invitation, for example because it restarted.", "run 'synesis link invite' again"),
            advice("LINK_FAILED", "The invitation ended without a new connection.", "see LINK_FAILURE and LINK_FAILURE_DETAIL, then repeat the command"),
            advice("LINK_WAIT_TIMEOUT", "Nobody opened the invitation before --wait-seconds ran out.", "send the link again and repeat the command, or raise --wait-seconds"),
            advice("RUNTIME_REQUEST_REJECTED", "The project's runtime refused the request.", "run 'synesis doctor' and repeat the command"),

            advice("TASK_REQUIRED", "agents run needs a task", "pass --prompt \"<task>\" or --prompt-file <file>"),
            advice("PROMPT_CONFLICT", "agents run takes either --prompt or --prompt-file, not both", "give only one of --prompt and --prompt-file"),
            advice("WAIT_FOR_TASK_CONFLICT", "--wait-for-task starts the agents without a task, so it takes neither --prompt nor --prompt-file", "give a task with --prompt or --prompt-file, or give none and use --wait-for-task"),
            advice("AGENT_TASK_INVALID", "A task is text, not blank, and at most 20000 characters.", "pass the task as text or with --file <file>"),
            advice("AGENT_INVALID", "an --agent must be provider[:model[:effort]]", "write it like claude, claude:sonnet or codex::medium"),
            advice("PROVIDER_NOT_INSTALLED", "the provider is not installed in this project", "run 'synesis provider install <provider>'"),
            advice("AGENT_RUN_INVALID", "An option of agents run is not valid.", "check the options with 'synesis agents run --help'"),
            advice("AGENT_RUN_INTERRUPTED","The agent run was interrupted.", "repeat 'synesis agents run'; agents that stopped are resumed"),
            advice("OPERATOR_KEY_UNAVAILABLE", "This command needs your operator key's passphrase, typed at a terminal, and no terminal could be asked.", "run the command in your own terminal, outside the agent and any script"),
            advice("OPERATOR_PASSPHRASE_WRONG", "The passphrase does not open the operator key.", "repeat the command and type the passphrase you chose when the key was created"),
            advice("OPERATOR_PASSPHRASE_MISMATCH", "The two passphrases differ, so no operator key was created.", "repeat the command and type the same passphrase twice"),
            advice("OPERATOR_PASSPHRASE_TOO_SHORT", "The passphrase is shorter than 8 characters.", "repeat the command with a longer passphrase"),
            advice("OPERATOR_KEY_MISSING", "This project's signed policy names an operator key that is not on this computer.", "run the command on the owner's computer, where the key was created, or run 'synesis project policy restore-key' with the key's 24 words"),
            advice("OPERATOR_WORDS_WRONG", "These 24 words are not the ones of the operator key this project registered.", "type the words shown when the project's policy was first signed, not the project's recovery words"),
            advice("OPERATOR_KEY_RESTORE_FAILED", "The restored operator key could not be stored on this computer.", "run 'synesis doctor' and repeat the command"),
            advice("POLICY_BASELINE_REQUIRED", "This project has no signed policy yet, so it has no operator key to restore.", "sign the policy with 'synesis project policy adopt', which makes the key and shows its words"),
            advice("OPERATOR_KEY_MISMATCH", "The operator key on this computer is not the one the project registered.", "run the command on the owner's computer, or restore the key file it created"),
            advice("POLICY_ADOPTION_NEEDS_CONFIRMATION", "The policy in .synesis/project.json differs from the committed one, or is not committed, so nothing was signed.", "run 'synesis project policy adopt --from head' to sign the committed one, or '--from file' for the working file"),
            advice("POLICY_UNAVAILABLE", "The project's signed policy could not be read from its log, so no gate runs and none is skipped.", "run 'synesis doctor' and repeat the command"),
            advice("POLICY_NOT_RECEIVED", "This computer has not received the owner's signed policy yet, so it will not integrate or run a gate.", "connect to the owner's computer and try again"),
            advice("POLICY_IS_SIGNED", "The project's validation is a signed decision, so the file cannot change it.", "use 'synesis project validation set' or 'clear', which sign the decision"),
            advice("POLICY_WRITE_FAILED", "The decision could not be written.", "run 'synesis doctor' and repeat the command"),
            advice("NOT_THE_OWNER", "Only the owner's computer decides the project's policy.", "run the command on the owner's computer"),
            advice("AGENT_RUN_FAILED", "The agent run failed before it finished.","see REASON, run 'synesis doctor', then repeat 'synesis agents run'"));

    private ErrorShape() {
    }

    /**
     * Prints {@code ERROR}, {@code MESSAGE} and {@code FIX} for a code this class explains; a code it does not know
     * still gets a message and a fix, so no refusal prints a bare code.
     *
     * @param line where each line goes, normally {@code terminal::stderr}
     * @param code stable error code
     */
    public static void print(Consumer<String> line, String code) {
        line.accept("ERROR=" + code);
        explain(line, code);
    }

    /**
     * Prints the shape with a message the refusal itself carries and the fix this class has for the code.
     *
     * @param line    where each line goes
     * @param code    stable error code
     * @param message one sentence saying what happened
     */
    public static void print(Consumer<String> line, String code, String message) {
        line.accept("ERROR=" + code);
        line.accept("MESSAGE=" + Objects.requireNonNull(message, "message"));
        line.accept("FIX=" + KNOWN.getOrDefault(code, new Advice(UNLISTED_MESSAGE, UNLISTED_FIX)).fix());
    }

    /**
     * Prints the shape with a message and fix that depend on the input of the refusal.
     *
     * @param line    where each line goes
     * @param code    stable error code
     * @param message one sentence saying what happened
     * @param fix     one command or step that resolves it
     */
    public static void print(Consumer<String> line, String code, String message, String fix) {
        line.accept("ERROR=" + code);
        line.accept("MESSAGE=" + Objects.requireNonNull(message, "message"));
        line.accept("FIX=" + Objects.requireNonNull(fix, "fix"));
    }

    /**
     * Prints {@code MESSAGE} and {@code FIX} after a result that already carries {@code ERROR=<code>}.
     *
     * @param line where each line goes
     * @param code the code that was printed
     */
    public static void explain(Consumer<String> line, String code) {
        Advice advice = KNOWN.getOrDefault(code, new Advice(UNLISTED_MESSAGE, UNLISTED_FIX));
        line.accept("MESSAGE=" + advice.message());
        line.accept("FIX=" + advice.fix());
    }

    /**
     * Returns the codes this class explains.
     *
     * @return every code this class explains, which {@code docs/cli/command-reference.md} must list
     */
    public static java.util.Set<String> codes() {
        return KNOWN.keySet();
    }

    /**
     * Tells whether this class explains a code.
     *
     * @param code error code
     * @return whether this class has its own message and fix for the code
     */
    public static boolean explains(String code) {
        return KNOWN.containsKey(code);
    }
}
