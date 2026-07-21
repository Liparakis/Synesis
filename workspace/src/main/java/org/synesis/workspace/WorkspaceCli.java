package org.synesis.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.transport.Onboarding;
import org.synesis.link.transport.OnboardingFailure;
import org.synesis.link.transport.OnboardingFailureCode;
import org.synesis.link.transport.OnboardingEventType;
import org.synesis.projectrecord.DecisionEvidence;
import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.DecisionStatus;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.Ed25519Signer;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectRecordSync;

/**
 * JDK-only bootstrap launcher for one isolated Synesis workspace profile.
 *
 * <p>This application composes the existing Link identity and project-record
 * APIs. It owns no network protocol, record encoding, or durable format. The
 * launcher is process-confined and does not start background work.
 *
 * @since 1.0
 */
public final class WorkspaceCli {
    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_ARGUMENT_BYTES = 8_192;
    private static final int MAX_PROFILE_BYTES = 1_024;
    private static final Path PROJECT_CONFIG = Path.of("project.conf");

    private WorkspaceCli() { }

    /**
     * Runs one bounded workspace command and exits with a stable status.
     *
     * @param arguments launcher arguments; profile paths and values are never
     *                 emitted in failure output
     */
    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    static int run(String[] arguments) {
        try {
            validateArguments(arguments);
            if (arguments.length == 1 && "--help".equals(arguments[0])) {
                printUsage();
                return 0;
            }
            Parsed parsed = parse(arguments);
            return execute(parsed);
        } catch (WorkspaceFailure failure) {
            System.err.println("ERROR=" + failure.code);
            return 10;
        } catch (Exception failure) {
            System.err.println("ERROR=INTERNAL");
            return 10;
        }
    }

    private static int execute(Parsed parsed) throws Exception {
        return switch (parsed.command) {
            case "identity" -> executeIdentity(parsed);
            case "project" -> executeProject(parsed);
            case "decision" -> executeDecision(parsed);
            case "sync" -> executeSync(parsed);
            default -> throw failure("USAGE");
        };
    }

    private static int executeIdentity(Parsed parsed) throws Exception {
        if (!"show".equals(parsed.subcommand) || !parsed.options.isEmpty()) {
            throw failure("USAGE");
        }
        NodeIdentity identity = identity(parsed.profile);
        System.out.println("NODE_ID=" + identity.nodeId());
        return 0;
    }

    private static int executeProject(Parsed parsed) throws Exception {
        if (!"create".equals(parsed.subcommand) || parsed.options.size() != 1
                || !parsed.options.containsKey("--peer")) {
            throw failure("USAGE");
        }
        String peer = bounded(parsed.options.get("--peer"), 128, "peer");
        if (!peer.matches("sl1-[0-9a-f]{64}")) throw failure("PROJECT_INVALID");
        Path configPath = parsed.profile.resolve(PROJECT_CONFIG);
        if (Files.exists(configPath)) {
            try {
                ProjectConfig existing = ProjectConfig.load(configPath);
                throw failure(existing.peerNodeIds().contains(peer) && existing.peerNodeIds().size() == 1
                        ? "PROJECT_EXISTS" : "PROJECT_MISMATCH");
            } catch (WorkspaceFailure failure) {
                throw failure;
            } catch (Exception malformed) {
                throw failure("PROJECT_EXISTS");
            }
        }
        NodeIdentity identity = identity(parsed.profile);
        ProjectConfig config = new ProjectConfig(UUID.randomUUID(), java.util.Set.of(peer));
        try {
            config.save(configPath);
        } catch (Exception failure) {
            throw failure("PROJECT_WRITE_FAILED");
        }
        System.out.println("NODE_ID=" + identity.nodeId());
        System.out.println("PROJECT_ID=" + config.projectId());
        System.out.println("PEER_NODE_ID=" + peer);
        System.out.println("PROJECT_CONFIGURED=true");
        return 0;
    }

    private static int executeDecision(Parsed parsed) throws Exception {
        if (!"create".equals(parsed.subcommand)) throw failure("USAGE");
        requireOptions(parsed.options, "--title", "--rationale", "--evidence-kind",
                "--evidence-ref", "--evidence-sha256");
        String title = bounded(parsed.options.get("--title"), DecisionRecord.MAX_TITLE_BYTES, "title");
        String rationale = bounded(parsed.options.get("--rationale"), DecisionRecord.MAX_RATIONALE_BYTES, "rationale");
        String kind = bounded(parsed.options.get("--evidence-kind"), 64, "evidence kind");
        String reference = bounded(parsed.options.get("--evidence-ref"), 1_024, "evidence reference");
        byte[] evidenceDigest = parseDigest(parsed.options.get("--evidence-sha256"));
        if (parsed.options.size() != 5) throw failure("USAGE");
        Path configPath = parsed.profile.resolve(PROJECT_CONFIG);
        ProjectConfig config;
        try {
            config = ProjectConfig.load(configPath);
        } catch (Exception failure) {
            throw failure("PROJECT_NOT_CONFIGURED");
        }
        NodeIdentity identity = identity(parsed.profile);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DecisionRecord record = DecisionRecord.create(config.projectId(), UUID.randomUUID(), 1, null,
                identity.nodeId(), identity.nodeId(), DecisionStatus.PROPOSED, now, now, title, rationale,
                java.util.List.of(new DecisionEvidence(kind, reference, evidenceDigest)),
                Ed25519Signer.from(identity));
        DecisionStore store;
        try {
            store = new DecisionStore(parsed.profile.resolve("records"), config.projectId());
            if (store.save(record, null) != DecisionStore.SaveResult.APPLIED) {
                throw failure("RECORD_WRITE_FAILED");
            }
        } catch (WorkspaceFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("RECORD_WRITE_FAILED");
        }
        System.out.println("NODE_ID=" + identity.nodeId());
        System.out.println("PROJECT_ID=" + record.projectId());
        System.out.println("RECORD_ID=" + record.recordId());
        System.out.println("REVISION=" + record.revision());
        System.out.println("DIGEST=" + record.digestHex());
        System.out.println("STATUS=" + record.status());
        System.out.println("SIGNATURE_VALID=" + record.verify());
        return 0;
    }

    private static int executeSync(Parsed parsed) throws Exception {
        if (!"host".equals(parsed.subcommand) && !"join".equals(parsed.subcommand)) {
            throw failure("USAGE");
        }
        if ("host".equals(parsed.subcommand)) {
            if (!parsed.options.isEmpty() || parsed.positional != null) throw failure("USAGE");
            return host(parsed.profile);
        }
        if (parsed.options.size() != 3 || parsed.positional == null
                || !parsed.options.containsKey("--project") || !parsed.options.containsKey("--record")
                || !parsed.options.containsKey("--expect-host")) throw failure("USAGE");
        UUID projectId = parseUuid(parsed.options.get("--project"), "PROJECT_INVALID");
        UUID recordId = parseUuid(parsed.options.get("--record"), "RECORD_INVALID");
        String expectedHost = bounded(parsed.options.get("--expect-host"), 128, "peer");
        if (!expectedHost.matches("sl1-[0-9a-f]{64}")) throw failure("AUTH_FAILED");
        String invitation = bounded(parsed.positional, org.synesis.link.protocol.SessionInvitation.MAX_LINK_CHARS,
                "invitation");
        return join(parsed.profile, projectId, recordId, expectedHost, invitation);
    }

    private static int host(Path profile) throws Exception {
        ProjectConfig config = loadConfig(profile);
        if (config.peerNodeIds().size() != 1) throw failure("PROJECT_INVALID");
        String expectedPeer = config.peerNodeIds().iterator().next();
        DecisionStore store = new DecisionStore(profile.resolve("records"), config.projectId());
        ProjectRecordSync endpoint = new ProjectRecordSync(config, store);
        Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> {
            if (event.type() == OnboardingEventType.SHARE_LINK) {
                System.out.println("INVITATION=" + event.value());
            }
        });
        try {
            onboarding.host(expectedPeer, endpoint.handler(), session -> { });
        } catch (OnboardingFailure failure) {
            throw failure(mapOnboarding(failure.code()));
        }
        return 0;
    }

    private static int join(Path profile, UUID projectId, UUID recordId, String expectedHost,
            String invitation) throws Exception {
        AtomicReference<WorkspaceFailure> callbackFailure = new AtomicReference<>();
        Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> { });
        try {
            onboarding.join(invitation, null, session -> {
                try {
                    System.out.println("AUTHENTICATED_REMOTE=" + session.remoteNodeId());
                    if (!session.isUsable() || !expectedHost.equals(session.remoteNodeId())) {
                        WorkspaceFailure failure = failure("AUTH_FAILED");
                        callbackFailure.set(failure);
                        closeQuietly(session);
                        throw new WorkspaceAbort(failure);
                    }
                    ProjectConfig config = existingOrCreateConfig(profile, projectId, expectedHost);
                    DecisionStore store = new DecisionStore(profile.resolve("records"), projectId);
                    ProjectRecordSync endpoint = new ProjectRecordSync(config, store);
                    ProjectRecordSync.SyncOutcome outcome = endpoint.sync(session, recordId);
                    System.out.println("PROJECT_ID=" + projectId);
                    System.out.println("RECORD_ID=" + recordId);
                    System.out.println("SYNC_RESULT=" + outcome.code());
                    if (outcome.code() != ProjectRecordSync.Code.APPLIED
                            && outcome.code() != ProjectRecordSync.Code.DUPLICATE) {
                        WorkspaceFailure failure = failure(outcome.code().name());
                        callbackFailure.set(failure);
                        closeQuietly(session);
                        throw new WorkspaceAbort(failure);
                    }
                } catch (WorkspaceAbort abort) {
                    throw abort;
                } catch (WorkspaceFailure failure) {
                    callbackFailure.set(failure);
                    closeQuietly(session);
                    throw new WorkspaceAbort(failure);
                } catch (Exception failure) {
                    WorkspaceFailure safe = failure("SYNC_FAILED");
                    callbackFailure.set(safe);
                    closeQuietly(session);
                    throw new WorkspaceAbort(safe);
                }
            });
        } catch (OnboardingFailure failure) {
            WorkspaceFailure callback = callbackFailure.get();
            if (callback != null) throw callback;
            throw failure(mapOnboarding(failure.code()));
        }
        return 0;
    }

    private static ProjectConfig existingOrCreateConfig(Path profile, UUID projectId, String expectedHost)
            throws Exception {
        Path path = profile.resolve(PROJECT_CONFIG);
        if (Files.exists(path)) {
            ProjectConfig existing;
            try {
                existing = ProjectConfig.load(path);
            } catch (Exception failure) {
                throw failure("PROJECT_INVALID");
            }
            if (!projectId.equals(existing.projectId()) || !existing.peerNodeIds().equals(Set.of(expectedHost))) {
                throw failure("PROJECT_MISMATCH");
            }
            return existing;
        }
        ProjectConfig created = new ProjectConfig(projectId, Set.of(expectedHost));
        try {
            created.save(path);
            return created;
        } catch (Exception failure) {
            throw failure("PROJECT_WRITE_FAILED");
        }
    }

    private static ProjectConfig loadConfig(Path profile) throws Exception {
        try {
            return ProjectConfig.load(profile.resolve(PROJECT_CONFIG));
        } catch (Exception failure) {
            throw failure("PROJECT_INVALID");
        }
    }

    private static void closeQuietly(PeerSession session) {
        try {
            session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
        } catch (Exception ignored) {
            // Link owns bounded cleanup; this is only a best-effort rejection close.
        }
    }

    private static String mapOnboarding(OnboardingFailureCode code) {
        return switch (code) {
            case INVITE_INVALID -> "INVITE_INVALID";
            case HOST_IDENTITY_MISMATCH -> "AUTH_FAILED";
            case IDENTITY_FAILED -> "IDENTITY_FAILED";
            case HOST_TIMEOUT -> "TRANSPORT_FAILED";
            case NO_USABLE_CANDIDATE, CONNECTION_FAILED, INTERNAL -> "TRANSPORT_FAILED";
        };
    }

    private static NodeIdentity identity(Path profile) throws Exception {
        try {
            return new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
        } catch (Exception failure) {
            throw failure("IDENTITY_FAILED");
        }
    }

    private static Parsed parse(String[] arguments) throws WorkspaceFailure {
        if (arguments.length < 3 || !"--profile".equals(arguments[0])) throw failure("USAGE");
        Path profile;
        try {
            String value = bounded(arguments[1], MAX_PROFILE_BYTES, "profile");
            profile = Path.of(value).toAbsolutePath().normalize();
        } catch (Exception failure) {
            throw failure("PROFILE_INVALID");
        }
        String command = arguments[2];
        String subcommand = arguments.length > 3 ? arguments[3] : "";
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (int index = 4; index < arguments.length; index += 2) {
            String name = arguments[index];
            if (!name.startsWith("--")) {
                if (!"sync".equals(command) || !"join".equals(subcommand) || positional != null) {
                    throw failure("USAGE");
                }
                positional = name;
                if (index != arguments.length - 1) throw failure("USAGE");
                break;
            }
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")
                    || options.put(name, arguments[index + 1]) != null) {
                throw failure("USAGE");
            }
        }
        if ("sync".equals(command) && "join".equals(subcommand) && positional == null) throw failure("USAGE");
        return new Parsed(profile, command, subcommand, Map.copyOf(options), positional);
    }

    private static void requireOptions(Map<String, String> options, String... names) throws WorkspaceFailure {
        for (String name : names) {
            if (!options.containsKey(name)) throw failure("USAGE");
        }
    }

    private static byte[] parseDigest(String value) throws WorkspaceFailure {
        String checked = bounded(value, 64, "evidence digest");
        if (!checked.matches("[0-9a-fA-F]{64}")) throw failure("RECORD_INVALID");
        try {
            return HexFormat.of().parseHex(checked);
        } catch (IllegalArgumentException failure) {
            throw failure("RECORD_INVALID");
        }
    }

    private static String bounded(String value, int maxBytes, String name) throws WorkspaceFailure {
        if (value == null || value.indexOf('\u0000') >= 0) throw failure("" + nameCode(name));
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxBytes) throw failure(nameCode(name));
        return value;
    }

    private static String nameCode(String name) {
        return switch (name) {
            case "profile" -> "PROFILE_INVALID";
            case "peer" -> "PROJECT_INVALID";
            case "title", "rationale", "evidence kind", "evidence reference", "evidence digest" -> "RECORD_INVALID";
            default -> "USAGE";
        };
    }

    private static void validateArguments(String[] arguments) throws WorkspaceFailure {
        if (arguments == null || arguments.length > MAX_ARGUMENTS) throw failure("USAGE");
        for (String argument : arguments) {
            if (argument == null || argument.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
                throw failure("USAGE");
            }
        }
    }

    private static WorkspaceFailure failure(String code) { return new WorkspaceFailure(code); }

    private static UUID parseUuid(String value, String code) throws WorkspaceFailure {
        bounded(value, 64, code.equals("PROJECT_INVALID") ? "project" : "record");
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw failure(code);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: synesis-workspace --profile <dir> identity show");
        System.out.println("       synesis-workspace --profile <dir> project create --peer <node-id>");
        System.out.println("       synesis-workspace --profile <dir> decision create --title <text>"
                + " --rationale <text> --evidence-kind <kind> --evidence-ref <ref>"
                + " --evidence-sha256 <64-hex>");
        System.out.println("       synesis-workspace --profile <dir> sync host");
        System.out.println("       synesis-workspace --profile <dir> sync join --project <uuid>"
                + " --record <uuid> --expect-host <node-id> <invitation>");
    }

    private record Parsed(Path profile, String command, String subcommand, Map<String, String> options,
            String positional) {
        private Parsed {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(subcommand, "subcommand");
            Objects.requireNonNull(options, "options");
        }
    }

    private static final class WorkspaceAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private WorkspaceAbort(WorkspaceFailure failure) {
            super(failure);
        }
    }

    private static final class WorkspaceFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        private WorkspaceFailure(String code) {
            super(code);
            this.code = code;
        }
    }
}
