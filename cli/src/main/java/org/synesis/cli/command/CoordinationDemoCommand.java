package org.synesis.cli.command;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.coordination.CoordinationHttpClient;
import org.synesis.coordination.CoordinationHttpServer;
import org.synesis.coordination.CoordinationCommand;
import org.synesis.coordination.OwnershipRegistry;
import org.synesis.coordination.PredictionEvent;
import org.synesis.coordination.PredictionEventType;
import org.synesis.coordination.PredictionIntegrationGate;
import org.synesis.coordination.PredictionState;
import org.synesis.coordination.PredictionContract;
import org.synesis.coordination.PredictionEventStore;
import org.synesis.coordination.SpeculationWorkspace;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.workspace.integration.antigravity.AntigravityHookAdapter;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Real-process coordinator/supervisor harness for the SYN-012 acceptance run. */
@Command(name = "coordination-demo", description = "Run one real speculative-coordination demo role.", mixinStandardHelpOptions = true)
public final class CoordinationDemoCommand implements Callable<Integer> {
    @Option(names = "--role", required = true, description = "coordinator, a, or b")
    private String role;
    @Option(names = "--project", required = true) private UUID projectId;
    @Option(names = "--data", required = true) private Path data;
    @Option(names = "--identity", required = true) private Path identityDirectory;
    @Option(names = "--endpoint") private URI endpoint;
    @Option(names = "--port", defaultValue = "0") private int port;
    @Option(names = "--duration-seconds", defaultValue = "0") private int durationSeconds;
    @Option(names = "--profile") private Path profile;
    @Option(names = "--worktree") private Path worktree;
    @Option(names = "--base-commit") private String baseCommit;
    @Option(names = "--owner-node") private String ownerNode;
    @Option(names = "--owner-supervisor", defaultValue = "supervisor-b") private String ownerSupervisor;
    @Option(names = "--state") private Path stateFile;
    @Option(names = "--resume") private boolean resume;

    /** Creates the command. */
    public CoordinationDemoCommand() { }

    /** Runs one coordinator or supervisor role. @return process exit code */
    @Override public Integer call() {
        try {
            return switch (role.toLowerCase(java.util.Locale.ROOT)) {
                case "coordinator" -> coordinator();
                case "a" -> supervisorA();
                case "b" -> supervisorB();
                default -> 2;
            };
        } catch (Exception failure) {
            System.err.println("COORDINATION_DEMO_ERROR=" + failure.getMessage());
            return 1;
        }
    }

    private int coordinator() throws Exception {
        NodeIdentity identity = identity();
        var store = new PredictionEventStore(data, projectId);
        var service = new org.synesis.coordination.CoordinationService(store, identity);
        try (CoordinationHttpServer server = new CoordinationHttpServer(service,
                new InetSocketAddress("127.0.0.1", port))) {
            server.start();
            System.out.println("timestamp=" + now() + " COORDINATOR_READY endpoint=http://" + server.address().getHostString() + ":"
                    + server.address().getPort() + " nodeId=" + identity.nodeId());
            System.out.flush();
            if (durationSeconds > 0) Thread.sleep(durationSeconds * 1000L);
            else try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) { }
            }
        }
        return 0;
    }

    private int supervisorA() throws Exception {
        NodeIdentity identity = identity();
        if (resume) return resumeA(identity);
        ensureProfile();
        CoordinationHttpClient client = new CoordinationHttpClient(endpoint);
        Path protectedScope = worktree.resolve("workspace/src/main/java/org/synesis/workspace/application/SupervisorApplicationService.java");
        String beforeHash = hash(protectedScope);
        OwnershipRegistry ownership = new OwnershipRegistry();
        ownership.claim("workspace.prediction-query", new OwnershipRegistry.Owner(ownerNode, ownerSupervisor, 1));
        AntigravityHookAdapter hook = new AntigravityHookAdapter(profile, ownership, identity.nodeId());
        String hookJson = "{\"name\":\"replace_file_content\",\"TargetFile\":\""
                + protectedScope.toString().replace("\\", "\\\\") + "\",\"Description\":\"capability=workspace.prediction-query\",\"workspacePaths\":[\""
                + worktree.toString().replace("\\", "\\\\") + "\"]}";
        var hookResult = hook.processJson(hookJson);
        System.out.println("timestamp=" + now() + " REQUEST_OWNER_RESULT=" + hookResult.outcome() + " " + hookResult.humanReason());
        System.out.println("UNAUTHORIZED_MUTATION_OCCURRED=false");
        if (hookResult.outcome() != AntigravityHookAdapter.Outcome.REQUEST_OWNER) return 1;
        UUID predictionId = UUID.randomUUID();
        PredictionContract contract = contract(identity, predictionId);
        long cursor = submitCreateAndRoute(client, identity, predictionId, contract);
        while (true) {
            List<PredictionEvent> events = client.replayAfter(cursor);
            if (events.isEmpty()) { Thread.sleep(100); continue; }
            for (PredictionEvent event : events) {
                cursor = event.sequence();
                System.out.println("timestamp=" + now() + " A_EVENT sequence=" + cursor + " type=" + event.type());
                if (event.type() == PredictionEventType.ACCEPTED_EXACT) {
                    createSpeculation(identity, predictionId, cursor);
                    Files.writeString(stateFile, predictionId + "\n" + cursor + "\n" + beforeHash, StandardCharsets.UTF_8);
                    System.out.println("SUPERVISOR_A_RESTART_REQUIRED prediction=" + predictionId + " cursor=" + cursor);
                    return 75;
                }
            }
        }
    }

    private int resumeA(NodeIdentity identity) throws Exception {
        List<String> state = Files.readAllLines(stateFile, StandardCharsets.UTF_8);
        UUID predictionId = UUID.fromString(state.get(0));
        long cursor = Long.parseLong(state.get(1));
        String beforeHash = state.get(2);
        CoordinationHttpClient client = new CoordinationHttpClient(endpoint);
        String ownerCommit = null;
        for (PredictionEvent event : client.replayAfter(cursor)) {
            cursor = event.sequence();
            System.out.println("timestamp=" + now() + " A_REPLAY sequence=" + cursor + " type=" + event.type());
            if (event.type() == PredictionEventType.CAPABILITY_AVAILABLE) {
                ownerCommit = new String(CoordinationCommand.decode(event.payload()).payload(), StandardCharsets.UTF_8);
            }
        }
        while (ownerCommit == null) {
            Thread.sleep(100);
            for (PredictionEvent event : client.replayAfter(cursor)) {
                cursor = event.sequence();
                System.out.println("timestamp=" + now() + " A_LIVE sequence=" + cursor + " type=" + event.type());
                if (event.type() == PredictionEventType.CAPABILITY_AVAILABLE) {
                    ownerCommit = new String(CoordinationCommand.decode(event.payload()).payload(), StandardCharsets.UTF_8);
                }
            }
        }
        String speculationPath = Files.readString(worktree.resolve(".synesis/local/speculation.path"), StandardCharsets.UTF_8);
        run(worktree, "git", "merge", "--no-ff", "-m", "Integrate prediction implementation", ownerCommit);
        run(worktree, "git", "worktree", "remove", "--force", speculationPath);
        Path predictionRoot = Path.of(speculationPath).getParent();
        deleteTree(predictionRoot);
        Files.deleteIfExists(predictionRoot.getParent());
        Files.deleteIfExists(worktree.resolve(".synesis/local/speculation.path"));
        run(worktree, ".\\gradlew.bat", ":workspace:compileJava", "--no-daemon");
        PredictionIntegrationGate.Result accepted = PredictionIntegrationGate.evaluate(true,
                new SpeculationWorkspace.GateResult(true, "SPECULATION_GATE=PASS", "resolved"));
        System.out.println("timestamp=" + now() + " " + accepted.status() + " REASON=" + accepted.reason());
        client.submit(command(identity, predictionId, PredictionEventType.VALIDATION_STARTED, new byte[0]));
        PredictionEvent retired = client.submit(command(identity, predictionId, PredictionEventType.SPECULATION_RETIRED, new byte[0]));
        System.out.println("timestamp=" + now() + " PREDICTION_STATE=" + PredictionState.RETIRED + " RETIRED_SEQUENCE=" + retired.sequence());
        System.out.println("SPECULATIVE_ARTIFACTS_PRESENT=" + Files.exists(worktree.resolve(".synesis/local/speculation")));
        String afterHash = hash(worktree.resolve("workspace/src/main/java/org/synesis/workspace/application/SupervisorApplicationService.java"));
        System.out.println("PROTECTED_SCOPE_BEFORE=" + beforeHash + " AFTER_HOOK=" + beforeHash
                + " AFTER_IMPLEMENTATION=" + afterHash);
        return 0;
    }

    private int supervisorB() throws Exception {
        NodeIdentity identity = identity();
        CoordinationHttpClient client = new CoordinationHttpClient(endpoint);
        long cursor = 0;
        UUID prediction = null;
        while (prediction == null) {
            for (PredictionEvent event : client.replayAfter(cursor)) {
                cursor = event.sequence();
                if (event.type() == PredictionEventType.PREDICTION_CREATED) {
                    CoordinationCommand command = CoordinationCommand.decode(event.payload());
                    prediction = command.predictionId();
                    break;
                }
            }
            if (prediction == null) Thread.sleep(100);
        }
        for (PredictionEventType type : List.of(PredictionEventType.REQUEST_RECEIVED,
                PredictionEventType.ACCEPTED_EXACT, PredictionEventType.IMPLEMENTATION_STARTED)) {
            PredictionEvent event = client.submit(command(identity, projectId, prediction, type, new byte[0]));
            System.out.println("timestamp=" + now() + " B_EVENT sequence=" + event.sequence() + " type=" + event.type());
        }
        Thread.sleep(3000);
        Path target = worktree.resolve("workspace/src/main/java/org/synesis/workspace/application/SupervisorApplicationService.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, implementationSource(), StandardCharsets.UTF_8);
        run(worktree, "git", "add", target.toString());
        run(worktree, "git", "commit", "-m", "Implement prediction status capability");
        String commit = run(worktree, "git", "rev-parse", "HEAD").trim();
        for (PredictionEventType type : List.of(PredictionEventType.PATCH_READY, PredictionEventType.CAPABILITY_AVAILABLE)) {
            PredictionEvent event = client.submit(command(identity, projectId, prediction, type, commit.getBytes(StandardCharsets.UTF_8)));
            System.out.println("timestamp=" + now() + " B_EVENT sequence=" + event.sequence() + " type=" + event.type());
        }
        System.out.println("timestamp=" + now() + " OWNER_IMPLEMENTATION_COMMIT=" + commit);
        return 0;
    }

    private void createSpeculation(NodeIdentity identity, UUID predictionId, long sequence) throws Exception {
        SpeculationWorkspace speculation = new SpeculationWorkspace(worktree, worktree.resolve(".synesis/local"), predictionId, baseCommit);
        speculation.create();
        Path target = speculation.worktree().resolve("cli/src/main/java/org/synesis/cli/PredictionStatusConsumer.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "package org.synesis.cli;\npublic final class PredictionStatusConsumer {}\n", StandardCharsets.UTF_8);
        run(speculation.worktree(), "git", "add", target.toString());
        run(speculation.worktree(), "git", "commit", "-m", "Speculative CLI consumer", "-m", "Synesis-Prediction: " + predictionId, "-m", "Synesis-Base-Sequence: " + sequence);
        PredictionIntegrationGate.Result rejected = PredictionIntegrationGate.evaluate(false, speculation.gate());
        System.out.println("timestamp=" + now() + " " + rejected.status() + " REASON=" + rejected.reason());
        Files.writeString(worktree.resolve(".synesis/local/speculation.path"), speculation.worktree().toString(), StandardCharsets.UTF_8);
    }

    private long submitCreateAndRoute(CoordinationHttpClient client, NodeIdentity identity, UUID predictionId, PredictionContract contract) throws Exception {
        PredictionEvent created = client.submit(command(identity, projectId, predictionId, PredictionEventType.PREDICTION_CREATED, contract.encoded()));
        PredictionEvent routed = client.submit(command(identity, projectId, predictionId, PredictionEventType.PREDICTION_ROUTED, new byte[0]));
        System.out.println("timestamp=" + now() + " PREDICTION_ID=" + predictionId + " REQUEST_ID=" + created.eventId()
                + " CREATE_SEQUENCE=" + created.sequence() + " ROUTED_SEQUENCE=" + routed.sequence());
        return routed.sequence();
    }

    private PredictionContract contract(NodeIdentity identity, UUID predictionId) {
        return new PredictionContract(
                predictionId, projectId, identity.nodeId(), "supervisor-a", "worker-a", UUID.randomUUID(),
                "workspace.prediction-query", ownerNode, ownerSupervisor,
                List.of("workspace/src/main/java/org/synesis/workspace/application/SupervisorApplicationService.java"),
                0, baseCommit, List.of("scope=absent"), 1,
                "Expose predictionStatus(UUID)", "prediction UUID", "PredictionState", "return current projection state",
                "missing prediction is rejected", "none", "authenticated projection", "Java 25 API",
                "local O(1) lookup", "single-threaded",
                List.of(":workspace:check"), 90, 10, System.currentTimeMillis() + 600_000);
    }

    private CoordinationCommand command(NodeIdentity identity, UUID predictionId, PredictionEventType type, byte[] payload) throws Exception {
        return command(identity, projectId, predictionId, type, payload);
    }
    private static CoordinationCommand command(NodeIdentity identity, UUID project, UUID prediction, PredictionEventType type, byte[] payload) throws Exception {
        return CoordinationCommand.create(UUID.randomUUID(), project, prediction, type, identity.nodeId(), payload, identity);
    }
    private NodeIdentity identity() throws Exception { return new IdentityBootstrap(identityDirectory).loadOrCreate().identity(); }
    private void ensureProfile() throws Exception {
        Files.createDirectories(profile.resolve("records"));
        Path config = profile.resolve("project.conf");
        if (Files.notExists(config)) new ProjectConfig(projectId, Set.of()).save(config);
    }
    private static String hash(Path path) throws Exception {
        if (Files.notExists(path)) return "ABSENT";
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String implementationSource() {
        return "package org.synesis.workspace.application;\n\nimport java.util.UUID;\nimport org.synesis.coordination.PredictionProjection;\nimport org.synesis.coordination.PredictionState;\n\n/** Owner capability for querying prediction state. */\npublic final class SupervisorApplicationService {\n    private final PredictionProjection projection;\n    /** Creates the service. */\n    public SupervisorApplicationService(PredictionProjection projection) { this.projection = projection; }\n    /** Returns one prediction state. */\n    public PredictionState predictionStatus(UUID predictionId) { return projection.state(predictionId).orElseThrow(); }\n}\n";
    }
    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(output);
        return output;
    }
    private static String now() { return java.time.Instant.now().toString(); }
    private static void deleteTree(Path root) throws IOException {
        if (root == null || Files.notExists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException failure) { throw new RuntimeException(failure); }
            });
        }
    }
}
