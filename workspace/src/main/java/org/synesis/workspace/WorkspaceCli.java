package org.synesis.workspace;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;

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
import org.synesis.projectrecord.DecisionSearch;
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

    private WorkspaceCli() {
    }

    /**
     * Runs one bounded workspace command and exits with a stable status.
     *
     * @param arguments launcher arguments; profile paths and values are never
     *                  emitted in failure output
     */
    static void main(String[] arguments) {
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
            String hint = getHint(failure.code);
            if (hint != null) {
                System.err.println("HINT=" + hint);
            }
            return 10;
        } catch (Exception failure) {
            System.err.println("ERROR=INTERNAL");
            System.err.println("HINT=Unexpected internal failure.");
            return 70;
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
        return switch (parsed.subcommand) {
            case "create" -> executeDecisionCreate(parsed);
            case "search" -> executeDecisionSearch(parsed);
            case "inspect" -> executeDecisionInspect(parsed);
            default -> throw failure("USAGE");
        };
    }

    private static int executeDecisionCreate(Parsed parsed) throws Exception {
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

    private static int executeDecisionSearch(Parsed parsed) throws Exception {
        for (String key : parsed.options.keySet()) {
            if (!key.equals("--text") && !key.equals("--record") && !key.equals("--status")
                    && !key.equals("--owner") && !key.equals("--limit")) {
                throw failure("USAGE");
            }
        }
        Path configPath = parsed.profile.resolve(PROJECT_CONFIG);
        ProjectConfig config;
        try {
            config = ProjectConfig.load(configPath);
        } catch (Exception failure) {
            throw failure("PROJECT_NOT_CONFIGURED");
        }
        String text = parsed.options.getOrDefault("--text", "");
        UUID recordId = null;
        if (parsed.options.containsKey("--record")) {
            recordId = parseUuid(parsed.options.get("--record"), "RECORD_INVALID");
        }
        DecisionStatus status = null;
        if (parsed.options.containsKey("--status")) {
            String val = parsed.options.get("--status");
            try {
                status = DecisionStatus.valueOf(val);
            } catch (IllegalArgumentException e) {
                throw failure("USAGE");
            }
        }
        String owner = null;
        if (parsed.options.containsKey("--owner")) {
            owner = parsed.options.get("--owner");
            if (owner == null || !owner.matches("sl1-[0-9a-f]{64}")) {
                throw failure("USAGE");
            }
        }
        int limit = DecisionSearch.MAX_RESULTS;
        if (parsed.options.containsKey("--limit")) {
            try {
                limit = Integer.parseInt(parsed.options.get("--limit"));
                if (limit <= 0 || limit > DecisionSearch.MAX_RESULTS) {
                    throw failure("USAGE");
                }
            } catch (NumberFormatException e) {
                throw failure("USAGE");
            }
        }
        DecisionStore store;
        try {
            store = new DecisionStore(parsed.profile.resolve("records"), config.projectId());
        } catch (Exception e) {
            throw failure("LOCAL_STATE_INVALID");
        }
        DecisionSearch search = new DecisionSearch(store);
        DecisionSearch.Query query;
        try {
            query = new DecisionSearch.Query(text, recordId, status, owner, limit);
        } catch (IllegalArgumentException e) {
            throw failure("USAGE");
        }
        DecisionSearch.SearchResult result = search.search(query);
        if (!result.isSuccessful()) {
            throw failure(result.errorCode().name());
        }
        System.out.print(result.render());
        return 0;
    }

    private static int executeDecisionInspect(Parsed parsed) throws Exception {
        if (!parsed.options.containsKey("--record") || parsed.options.size() != 1) {
            throw failure("USAGE");
        }
        UUID recordId = parseUuid(parsed.options.get("--record"), "RECORD_INVALID");
        Path configPath = parsed.profile.resolve(PROJECT_CONFIG);
        ProjectConfig config;
        try {
            config = ProjectConfig.load(configPath);
        } catch (Exception failure) {
            throw failure("PROJECT_NOT_CONFIGURED");
        }
        DecisionRecord record = validateSingleRecord(parsed.profile, config.projectId(), recordId);
        System.out.println("PROJECT_ID=" + record.projectId());
        System.out.println("RECORD_ID=" + record.recordId());
        System.out.println("VERSION=" + record.revision());
        System.out.println("REVISION=" + record.revision());
        System.out.println("DIGEST=" + record.digestHex());
        System.out.println("OWNER=" + record.ownerNodeId());
        System.out.println("OWNER_NODE_ID=" + record.ownerNodeId());
        System.out.println("AUTHOR=" + record.authorNodeId());
        System.out.println("AUTHOR_NODE_ID=" + record.authorNodeId());
        System.out.println("STATUS=" + record.status());
        String evidenceDigest = record.evidence().isEmpty() ? "" : record.evidence().getFirst().digestHex();
        System.out.println("EVIDENCE_DIGEST=" + evidenceDigest);
        boolean valid;
        try {
            valid = record.verify();
        } catch (Exception e) {
            valid = false;
        }
        System.out.println("SIGNATURE_VALID=" + valid);
        return 0;
    }

    private static DecisionRecord validateSingleRecord(Path profile, UUID projectId, UUID recordId) throws Exception {
        Path recordsDir = profile.resolve("records");
        Path decisionsDir = recordsDir.resolve("decisions").resolve(recordId.toString());
        Path headsDir = recordsDir.resolve("heads");
        Path headPath = headsDir.resolve(recordId + ".head");
        if (!Files.exists(headPath)) {
            throw failure("RECORD_NOT_FOUND");
        }
        byte[] headBytes = Files.readAllBytes(headPath);
        if (headBytes.length != 45) {
            throw failure("LOCAL_STATE_INVALID");
        }
        long headRevision;
        byte[] headDigest = new byte[32];
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(headBytes))) {
            int magic = input.readInt();
            int version = input.readUnsignedByte();
            if (magic != 0x53444831 || version != 1) {
                throw failure("LOCAL_STATE_INVALID");
            }
            headRevision = input.readLong();
            if (headRevision <= 0) {
                throw failure("LOCAL_STATE_INVALID");
            }
            input.readFully(headDigest);
            if (input.available() != 0) {
                throw failure("LOCAL_STATE_INVALID");
            }
        } catch (Exception e) {
            throw failure("LOCAL_STATE_INVALID");
        }
        if (!Files.isDirectory(decisionsDir)) {
            throw failure("LOCAL_STATE_INVALID");
        }
        List<Path> sdrFiles;
        try (var stream = Files.list(decisionsDir)) {
            sdrFiles = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sdr"))
                    .toList();
        }
        if (sdrFiles.isEmpty() || sdrFiles.size() > 64) {
            throw failure("LOCAL_STATE_INVALID");
        }
        Map<Long, Path> revisionMap = new HashMap<>();
        for (Path file : sdrFiles) {
            String name = file.getFileName().toString();
            long revisionNum;
            try {
                revisionNum = Long.parseLong(name.substring(0, name.length() - ".sdr".length()));
            } catch (Exception e) {
                throw failure("LOCAL_STATE_INVALID");
            }
            if (revisionMap.put(revisionNum, file) != null) {
                throw failure("LOCAL_STATE_INVALID");
            }
        }
        List<Long> sortedRevisions = revisionMap.keySet().stream().sorted().toList();
        if (sortedRevisions.isEmpty() || sortedRevisions.getFirst() != 1) {
            throw failure("LOCAL_STATE_INVALID");
        }
        DecisionRecord previous = null;
        for (long rev : sortedRevisions) {
            Path file = revisionMap.get(rev);
            byte[] fileBytes = Files.readAllBytes(file);
            DecisionRecord record;
            try {
                record = DecisionRecord.decode(fileBytes);
            } catch (Exception e) {
                throw failure("LOCAL_STATE_INVALID");
            }
            if (record.revision() != rev || !recordId.equals(record.recordId())
                    || !projectId.equals(record.projectId())) {
                throw failure("LOCAL_STATE_INVALID");
            }
            try {
                if (!record.verify()) {
                    throw failure("LOCAL_STATE_INVALID");
                }
            } catch (Exception e) {
                throw failure("LOCAL_STATE_INVALID");
            }
            if (previous == null) {
                if (record.revision() != 1 || record.previousDigest() != null) {
                    throw failure("LOCAL_STATE_INVALID");
                }
            } else {
                if (record.revision() != previous.revision() + 1
                        || !Arrays.equals(record.previousDigest(), previous.digest())) {
                    throw failure("LOCAL_STATE_INVALID");
                }
            }
            previous = record;
        }
        if (previous.revision() != headRevision || !Arrays.equals(previous.digest(), headDigest)) {
            throw failure("LOCAL_STATE_INVALID");
        }
        return previous;
    }

    private static int executeSync(Parsed parsed) throws Exception {
        if (!"host".equals(parsed.subcommand) && !"join".equals(parsed.subcommand)) {
            throw failure("USAGE");
        }
        if ("host".equals(parsed.subcommand)) {
            UUID projectId = null;
            UUID recordId = null;
            if (parsed.options.containsKey("--project")) {
                projectId = parseUuid(parsed.options.get("--project"), "PROJECT_INVALID");
            }
            if (parsed.options.containsKey("--record")) {
                recordId = parseUuid(parsed.options.get("--record"), "RECORD_INVALID");
            }
            for (String key : parsed.options.keySet()) {
                if (!"--project".equals(key) && !"--record".equals(key)) throw failure("USAGE");
            }
            if (parsed.positional != null) throw failure("USAGE");
            return host(parsed.profile, projectId, recordId);
        }

        // join
        if (parsed.positional == null) throw failure("USAGE");

        // 1. Parse URI and query params
        String link = parsed.positional;
        java.net.URI uri;
        try {
            uri = new java.net.URI(link);
        } catch (Exception e) {
            throw failure("USAGE");
        }

        Map<String, String> queryParams = parseQueryParams(uri.getQuery());

        // Strip query parameters to get clean link
        String cleanInvitationLink;
        try {
            java.net.URI cleanUri = new java.net.URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment());
            cleanInvitationLink = cleanUri.toString();
        } catch (Exception e) {
            throw failure("USAGE");
        }

        // 2. Decode the invitation from the clean link
        org.synesis.link.protocol.SessionInvitation invitation;
        try {
            invitation = org.synesis.link.protocol.SessionInvitation.fromShareLink(cleanInvitationLink);
        } catch (Exception e) {
            throw failure("INVITE_INVALID");
        }

        // 3. Derive host identity from signed invitation
        org.synesis.link.candidate.CandidateDescriptor hostDesc;
        try {
            hostDesc = org.synesis.link.candidate.CandidateDescriptor.decode(invitation.descriptorEncoded());
        } catch (Exception e) {
            throw failure("INVITE_INVALID");
        }
        String derivedHostNodeId = hostDesc.nodeId();

        // 4. Validate host= in URI if present
        String hostFromUri = queryParams.get("host");
        if (hostFromUri != null && !hostFromUri.equals(derivedHostNodeId)) {
            throw failure("AUTH_FAILED");
        }

        // 5. Determine project and record ID from CLI options or URI query parameters
        String projectStr = parsed.options.containsKey("--project")
                ? parsed.options.get("--project")
                : queryParams.get("project");
        if (projectStr == null) throw failure("USAGE");
        UUID projectId = parseUuid(projectStr, "PROJECT_INVALID");

        String recordStr = parsed.options.containsKey("--record")
                ? parsed.options.get("--record")
                : queryParams.get("record");
        if (recordStr == null) throw failure("USAGE");
        UUID recordId = parseUuid(recordStr, "RECORD_INVALID");

        // Check for unknown options on join
        for (String key : parsed.options.keySet()) {
            if (!"--project".equals(key) && !"--record".equals(key) && !"--expect-host".equals(key)) {
                throw failure("USAGE");
            }
        }

        // 6. Check expect-host option
        String expectHostCli = parsed.options.get("--expect-host");
        if (expectHostCli != null) {
            if (!expectHostCli.matches("sl1-[0-9a-f]{64}")) throw failure("AUTH_FAILED");
            if (!expectHostCli.equals(derivedHostNodeId)) {
                throw failure("AUTH_FAILED");
            }
        }

        // 7. Resolve project config & trust anchor
        ProjectConfig config = null;
        Path configPath = parsed.profile.resolve(PROJECT_CONFIG);
        if (Files.exists(configPath)) {
            try {
                config = ProjectConfig.load(configPath);
            } catch (Exception e) {
                throw failure("PROJECT_INVALID");
            }
        }

        if (config != null) {
            // Project config exists. Validate project ID!
            if (!config.projectId().equals(projectId)) {
                throw failure("PROJECT_MISMATCH");
            }
            // Validate peer Node ID!
            String configuredPeer = config.peerNodeIds().iterator().next();
            if (!configuredPeer.equals(derivedHostNodeId)) {
                throw failure("PEER_MISMATCH");
            }
        } else {
            // Project config does NOT exist.
            // We require explicit fingerprint confirmation via --expect-host CLI option!
            if (expectHostCli == null) {
                throw failure("PROJECT_NOT_CONFIGURED");
            }
            // Create project config with the confirmed host
            existingOrCreateConfig(parsed.profile, projectId, derivedHostNodeId);
        }

        // 8. Run sync
        return join(parsed.profile, projectId, recordId, derivedHostNodeId, cleanInvitationLink);
    }

    private static int host(Path profile, UUID projectId, UUID recordId) throws Exception {
        ProjectConfig config = loadConfig(profile);
        if (config.peerNodeIds().size() != 1) throw failure("PROJECT_INVALID");
        String expectedPeer = config.peerNodeIds().iterator().next();

        if (projectId != null && !projectId.equals(config.projectId())) {
            throw failure("PROJECT_MISMATCH");
        }

        DecisionStore store = new DecisionStore(profile.resolve("records"), config.projectId());

        if (recordId != null) {
            try {
                validateSingleRecord(profile, config.projectId(), recordId);
            } catch (Exception e) {
                throw failure("RECORD_NOT_FOUND");
            }
        }

        ProjectRecordSync endpoint = new ProjectRecordSync(config, store);
        String hostNode = new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity().nodeId();
        Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> {
            if (event.type() == OnboardingEventType.SHARE_LINK) {
                String composed = composeWorkspaceUri(event.value(), config.projectId(), recordId, hostNode);
                System.out.println("INVITATION=" + composed);
            }
        });
        try {
            onboarding.host(expectedPeer, endpoint.handler(), _ -> {
            });
        } catch (OnboardingFailure failure) {
            throw failure(mapOnboarding(failure.code()));
        }
        return 0;
    }

    private static int join(Path profile, UUID projectId, UUID recordId, String expectedHost,
                            String invitation) throws Exception {
        AtomicReference<WorkspaceFailure> callbackFailure = new AtomicReference<>();
        Onboarding onboarding = new Onboarding(profile.resolve("link"), _ -> {
        });
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
            case HOST_TIMEOUT, NO_USABLE_CANDIDATE, CONNECTION_FAILED, INTERNAL -> "TRANSPORT_FAILED";
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
                if (!"sync".equals(command) || !"join".equals(subcommand)) {
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
        if (value == null || value.indexOf('\u0000') >= 0) throw failure(nameCode(name));
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

    private static WorkspaceFailure failure(String code) {
        return new WorkspaceFailure(code);
    }

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
        System.out.println("       synesis-workspace --profile <dir> decision search [--text <text>]"
                + " [--record <uuid>] [--status <status>] [--owner <node-id>] [--limit <int>]");
        System.out.println("       synesis-workspace --profile <dir> decision inspect --record <uuid>");
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
        @Serial
        private static final long serialVersionUID = 1L;

        private WorkspaceAbort(WorkspaceFailure failure) {
            super(failure);
        }
    }

    private static final class WorkspaceFailure extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String code;

        private WorkspaceFailure(String code) {
            super(code);
            this.code = code;
        }
    }

    private static String composeWorkspaceUri(String inviteLink, UUID projectId, UUID recordId, String hostNodeId) {
        StringBuilder sb = new StringBuilder(inviteLink);
        boolean first = true;
        if (projectId != null) {
            sb.append("?project=").append(projectId);
            first = false;
        }
        if (recordId != null) {
            sb.append(first ? "?" : "&").append("record=").append(recordId);
            first = false;
        }
        if (hostNodeId != null) {
            sb.append(first ? "?" : "&").append("host=").append(hostNodeId);
        }
        return sb.toString();
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                params.put(key, value);
            }
        }
        return params;
    }

    private static String getHint(String code) {
        return switch (code) {
            case "PROFILE_INVALID" -> "Specify a valid absolute path to a profile directory.";
            case "IDENTITY_FAILED" -> "Local identity files are missing or could not be loaded.";
            case "PROJECT_INVALID", "PROJECT_NOT_CONFIGURED" ->
                    "Project is not configured. Run 'project create --peer <host-node-id>' first.";
            case "PROJECT_MISMATCH" ->
                    "Project ID or configured peer Node ID does not match the workspace configuration.";
            case "PEER_MISMATCH" -> "The host node identity does not match the configured peer for this project.";
            case "RECORD_INVALID" -> "The decision record format or inputs are invalid.";
            case "RECORD_NOT_FOUND" -> "The requested record was not found or is missing.";
            case "AUTH_FAILED" -> "The remote host public identity did not match the expected pinned host Node ID.";
            case "TRANSPORT_FAILED" ->
                    "The bounded session connection failed. Verify the network and that the host is running.";
            case "INVITE_INVALID" -> "The connection link is invalid, expired, or malformed.";
            case "LOCAL_STATE_INVALID" -> "The local storage contains corrupt or inconsistent record files.";
            case "SCAN_LIMIT" -> "The directory scan exceeds the maximum allowed files limit.";
            case "USAGE" -> "Check command syntax and options using --help.";
            default -> null;
        };
    }
}
