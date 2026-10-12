package org.synesis.cli.command.conversation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.conversation.ConversationService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shows the operator what agents said to each other (ADR-0093). It only reads: the operator can
 * read every conversation and cannot post in one.
 */
@Command(name = "conversations", description = "Read what agents said to each other. Without an identifier, lists the conversations; with one, prints its messages.",
        mixinStandardHelpOptions = true)
public final class ConversationsCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    @Parameters(index = "0", arity = "0..1", description = "Conversation identifier, as the list prints it.")
    private String conversation;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ConversationsCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        try {
            PredictionEventStore store = ProjectOptions.locate(runtime, project).openCoordinationStore();
            Map<UUID, List<ConversationMessage>> conversations = new LinkedHashMap<>();
            for (ConversationMessage message : store.conversationProjection().messages()) {
                conversations.computeIfAbsent(message.conversationId(), id -> new java.util.ArrayList<>()).add(message);
            }
            if (conversation == null) {
                runtime.terminal().stdout("CONVERSATIONS=" + conversations.size());
                conversations.forEach((id, said) -> runtime.terminal().stdout("CONVERSATION=" + id + " MESSAGES=" + said.size() + " LAST=" + Instant.ofEpochMilli(said.getLast().sentAtEpochMillis())
                        + " MEMBERS=" + said.getFirst().members().stream().map(member -> label(store, member)).toList()));
                return ExitCodes.OK;
            }
            List<ConversationMessage> said = conversations.get(UUID.fromString(conversation));
            if (said == null) {
                runtime.terminal().stderr("CONVERSATION_NOT_FOUND=" + conversation);
                return ExitCodes.COMMAND_FAILED;
            }
            runtime.terminal().stdout("CONVERSATION=" + conversation + " MESSAGES=" + said.size());
            for (ConversationMessage message : said) {
                runtime.terminal().stdout("MESSAGE=" + message.sequence() + " AT=" + Instant.ofEpochMilli(message.sentAtEpochMillis()) + " FROM=" + label(store, message.senderLineageId()));
                message.text().lines().forEach(line -> runtime.terminal().stdout("TEXT=" + line));
            }
            return ExitCodes.OK;
        } catch (IllegalArgumentException notAnIdentifier) {
            runtime.terminal().stderr("CONVERSATION_NOT_FOUND=" + conversation);
            return ExitCodes.COMMAND_FAILED;
        } catch (Exception failure) {
            runtime.terminal().stderr("CONVERSATIONS_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    /** Names a member lane by the agent holding it and its files; a lane that has ended keeps the name it ended with. */
    private static String label(PredictionEventStore store, UUID lineage) {
        Map<String, Object> member = ConversationService.memberMap(store.collaborationProjection(), lineage);
        String ended = member.containsKey("lane") ? "(ended)" : "";
        return member.containsKey("participant") ? member.get("participant") + String.valueOf(member.get("claims")) + ended : "ended-lane:" + lineage;
    }
}
