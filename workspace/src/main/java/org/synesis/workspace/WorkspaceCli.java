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

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.DecisionEvidence;
import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.DecisionStatus;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.Ed25519Signer;
import org.synesis.projectrecord.ProjectConfig;

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
        for (int index = 4; index < arguments.length; index += 2) {
            String name = arguments[index];
            if (!name.startsWith("--") || index + 1 >= arguments.length || options.put(name, arguments[index + 1]) != null) {
                throw failure("USAGE");
            }
        }
        return new Parsed(profile, command, subcommand, Map.copyOf(options));
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

    private static void printUsage() {
        System.out.println("Usage: synesis-workspace --profile <dir> identity show");
        System.out.println("       synesis-workspace --profile <dir> project create --peer <node-id>");
        System.out.println("       synesis-workspace --profile <dir> decision create --title <text>"
                + " --rationale <text> --evidence-kind <kind> --evidence-ref <ref>"
                + " --evidence-sha256 <64-hex>");
    }

    private record Parsed(Path profile, String command, String subcommand, Map<String, String> options) {
        private Parsed {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(subcommand, "subcommand");
            Objects.requireNonNull(options, "options");
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
