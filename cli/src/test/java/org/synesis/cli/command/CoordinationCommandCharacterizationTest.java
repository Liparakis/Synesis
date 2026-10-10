package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.command.CliHarness.Result;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Records stdout, stderr and exit codes of the coordination commands that sign and submit events or replay them, against a real in-process coordination endpoint.
 */
class CoordinationCommandCharacterizationTest {

    private static final List<String> NONE = List.of();
    private static final String ID = CliHarness.UUID_SHAPE;
    private static final UUID TASK = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @TempDir
    Path tmp;
    private CoordinationHttpServer server;
    private String endpoint;
    private String project;
    private String profile;
    private String projectId;

    @BeforeEach
    void startEndpoint() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        new ProjectApplicationService().init(root);
        UUID id = new ProjectApplicationService().require(root).projectId();
        server = new CoordinationHttpServer(new CoordinationService(new PredictionEventStore(tmp.resolve("data"), id), NodeIdentity.generate()), new InetSocketAddress("127.0.0.1", 0));
        server.start();
        endpoint = "http://127.0.0.1:" + server.address().getPort() + "/";
        project = root.toString();
        profile = tmp.resolve("profile").toString();
        projectId = id.toString();
    }

    @AfterEach
    void stopEndpoint() {
        server.close();
    }

    private Result run(String... arguments) {
        return CliHarness.run(tmp.resolve("cli-profile"), arguments);
    }

    private static String[] join(String[] command, String... options) {
        List<String> all = new ArrayList<>(List.of(command));
        all.addAll(List.of(options));
        return all.toArray(String[]::new);
    }

    private String[] signed(String... command) {
        return join(command, "--project", project, "--endpoint", endpoint, "--profile", profile, "--supervisor", "sup-1");
    }

    private String[] task(String... command) {
        return join(signed(command), "--worker", "wrk-1");
    }

    private String[] ownership(String taskId, String... command) {
        return join(signed(command), "--task", taskId, "--capability", "cap-a", "--scope", "a/**,b/**");
    }

    private String claimedTask() {
        Result created = run(join(task("task", "create"), "--title", "Title", "--capability", "cap-a"));
        String taskId = firstValue(created, "TASK_ID");
        run(join(task("task", "claim"), "--task", taskId));
        return taskId;
    }

    private String nodeId() throws Exception {
        return CoordinationCliSupport.loadIdentity(Path.of(profile)).nodeId();
    }

    private String[] predictionCreate() throws Exception {
        return join(task("prediction", "create"), "--task", TASK.toString(), "--capability", "cap-a", "--owner-node", nodeId(), "--owner-supervisor", "sup-1", "--scope", "a/**", "--base-commit", "HEAD",
                "--base-scope-hash", "a/**=absent", "--purpose", "p", "--inputs", "i", "--outputs", "o", "--behavior", "b", "--errors", "e", "--side-effects", "none", "--invariants", "inv",
                "--compatibility", "c", "--performance", "perf", "--concurrency", "conc", "--acceptance-test", "t1,t2");
    }

    private static String firstValue(Result result, String key) {
        return result.stdout().stream().filter(line -> line.startsWith(key + "=")).findFirst().orElseThrow().substring(key.length() + 1);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void ownershipClaimShowAndReleaseRoundTrip() throws Exception {
        String show = "--endpoint";
        String taskId = claimedTask();
        run("ownership", "show", show, endpoint, "--capability", "cap-a").assertIs(10, NONE, List.of("OWNERSHIP_ERROR=OWNERSHIP_NOT_FOUND"));

        run(ownership(taskId, "ownership", "claim")).assertIs(0, List.of("OWNERSHIP_CLAIMED=true", "CAPABILITY=cap-a", "PROJECT_SEQUENCE=3"), NONE);
        run("ownership", "show", show, endpoint, "--capability", "cap-a").assertIs(0,
                List.of("CAPABILITY=cap-a", "OWNER_NODE=" + nodeId(), "OWNER_SUPERVISOR=sup-1", "TASK_ID=" + taskId, "INTENT_VERSION=1"), NONE);

        run(ownership(taskId, "ownership", "release")).assertIs(0, List.of("OWNERSHIP_RELEASED=true", "CAPABILITY=cap-a", "PROJECT_SEQUENCE=4"), NONE);
        run("ownership", "show", show, endpoint, "--capability", "cap-a").assertIs(10, NONE, List.of("OWNERSHIP_ERROR=OWNERSHIP_NOT_FOUND"));
    }

    @Test
    void taskCreateClaimAndShowRoundTrip() {
        run("task", "show", "--project", project, "--endpoint", endpoint, "--task", TASK.toString()).assertIs(10, NONE, List.of("TASK_ERROR=TASK_NOT_FOUND"));

        Result created = run(join(task("task", "create"), "--title", "Title", "--capability", "cap-a"));
        created.assertIs(0, List.of("TASK_CREATED=true", "~TASK_ID=" + ID, "PROJECT_SEQUENCE=1"), NONE);
        String taskId = firstValue(created, "TASK_ID");

        run("task", "show", "--project", project, "--endpoint", endpoint, "--task", taskId).assertIs(0,
                List.of("TASK_ID=" + taskId, "PROJECT_ID=" + projectId, "TITLE=Title", "CAPABILITY=cap-a", "OWNER_NODE=UNCLAIMED"), NONE);

        run(join(task("task", "claim"), "--task", taskId)).assertIs(0, List.of("TASK_CLAIMED=true", "TASK_ID=" + taskId, "PROJECT_SEQUENCE=2"), NONE);
        run("task", "show", "--project", project, "--endpoint", endpoint, "--task", taskId).assertIs(0,
                List.of("TASK_ID=" + taskId, "PROJECT_ID=" + projectId, "TITLE=Title", "CAPABILITY=cap-a", "~OWNER_NODE=(?!UNCLAIMED$)\\S+"), NONE);
    }

    @Test
    void predictionListCreateRespondPublishAndShowRoundTrip() throws Exception {
        run("prediction", "list", "--project", project, "--endpoint", endpoint).assertIs(0, List.of("PROJECT_ID=" + projectId, "PREDICTIONS=0"), NONE);

        Result created = run(predictionCreate());
        created.assertIs(0, List.of("PREDICTION_CREATED=true", "~PREDICTION_ID=" + ID, "~REQUEST_ID=" + ID, "CREATE_SEQUENCE=1", "ROUTED_SEQUENCE=2"), NONE);
        String prediction = firstValue(created, "PREDICTION_ID");

        run("prediction", "list", "--project", project, "--endpoint", endpoint).assertIs(0,
                List.of("PROJECT_ID=" + projectId, "PREDICTION_ID=" + prediction + " STATE=ROUTED", "PREDICTIONS=1"), NONE);

        run(join(task("prediction", "respond"), "--prediction", prediction, "--action", "receive")).assertIs(0,
                List.of("PREDICTION_RESPONSE=REQUEST_RECEIVED", "PREDICTION_ID=" + prediction, "PROJECT_SEQUENCE=3"), NONE);
        run(join(task("prediction", "respond"), "--prediction", prediction, "--action", "exact")).assertIs(0,
                List.of("PREDICTION_RESPONSE=ACCEPTED_EXACT", "PREDICTION_ID=" + prediction, "PROJECT_SEQUENCE=4"), NONE);
        run(join(task("prediction", "publish"), "--prediction", prediction, "--stage", "implementation-started", "--commit", "abc123")).assertIs(0,
                List.of("PREDICTION_PUBLISHED=IMPLEMENTATION_STARTED", "PREDICTION_ID=" + prediction, "PROJECT_SEQUENCE=5"), NONE);

        run("prediction", "show", "--project", project, "--endpoint", endpoint, "--prediction", prediction).assertIs(0, List.of("PREDICTION_ID=" + prediction, "PROJECT_ID=" + projectId,
                "~STATE=[A-Z_]+", "CAPABILITY=cap-a", "OWNER_NODE=" + nodeId(), "OWNER_SUPERVISOR=sup-1", "PROJECT_SEQUENCE=5"), NONE);
        run("prediction", "show", "--project", project, "--endpoint", endpoint, "--prediction", UUID.randomUUID().toString()).assertIs(10, NONE, List.of("PREDICTION_ERROR=PREDICTION_NOT_FOUND"));
    }

    @Test
    void predictionRespondAndPublishRejectUnknownActionsBeforeSubmitting() {
        String prediction = UUID.randomUUID().toString();
        run(join(task("prediction", "respond"), "--prediction", prediction, "--action", "bogus")).assertIs(10, NONE, List.of("PREDICTION_ERROR=INVALID_RESPONSE_ACTION"));
        run(join(task("prediction", "publish"), "--prediction", prediction, "--stage", "bogus", "--commit", "c")).assertIs(10, NONE, List.of("PREDICTION_ERROR=INVALID_PUBLISH_STAGE"));
    }

    @Test
    void speculationInvalidateSubmitsAndToleratesMissingSpeculationMetadata() throws Exception {
        String prediction = firstValue(run(predictionCreate()), "PREDICTION_ID");
        run("speculation", "invalidate", "--project", project, "--endpoint", endpoint, "--profile", profile, "--prediction", prediction).assertIs(0, List.of("PREDICTION_INVALIDATED=true"), NONE);
    }

    @Test
    void speculationRetireAndValidateFailWithoutPreparedSpeculation() {
        String prediction = UUID.randomUUID().toString();
        run("speculation", "retire", "--project", project, "--endpoint", endpoint, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("~SPECULATION_ERROR=.+"));
        run("speculation", "validate", "--project", project, "--endpoint", endpoint, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("~SPECULATION_ERROR=.+"));
    }

    @Test
    void unreachableEndpointFailsWithTheCommandErrorLine() throws Exception {
        String dead = "http://127.0.0.1:" + freePort() + "/";
        String prediction = UUID.randomUUID().toString();
        // Records current behavior: the connection failure carries no message, so the line ends in "null".
        List<String> task = List.of("TASK_ERROR=null");
        run(withEndpoint(dead, join(task("task", "create"), "--title", "T", "--capability", "c"))).assertIs(10, NONE, task);
        run(withEndpoint(dead, join(task("task", "claim"), "--task", TASK.toString()))).assertIs(10, NONE, task);
        run(withEndpoint(dead, ownership(TASK.toString(), "ownership", "claim"))).assertIs(10, NONE, List.of("OWNERSHIP_ERROR=null"));
        run(withEndpoint(dead, ownership(TASK.toString(), "ownership", "release"))).assertIs(10, NONE, List.of("OWNERSHIP_ERROR=null"));
        run(withEndpoint(dead, predictionCreate())).assertIs(10, NONE, List.of("PREDICTION_ERROR=null"));
        run(withEndpoint(dead, join(task("prediction", "respond"), "--prediction", prediction, "--action", "exact"))).assertIs(10, NONE, List.of("PREDICTION_ERROR=null"));
        run(withEndpoint(dead, join(task("prediction", "publish"), "--prediction", prediction, "--stage", "patch-ready", "--commit", "c"))).assertIs(10, NONE, List.of("PREDICTION_ERROR=null"));
        run("speculation", "retire", "--project", project, "--endpoint", dead, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("SPECULATION_ERROR=null"));
        run("speculation", "invalidate", "--project", project, "--endpoint", dead, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("SPECULATION_ERROR=null"));
        run("ownership", "show", "--endpoint", dead, "--capability", "cap-a").assertIs(10, NONE, List.of("OWNERSHIP_ERROR=null"));
        run("task", "show", "--project", project, "--endpoint", dead, "--task", TASK.toString()).assertIs(10, NONE, task);
        run("prediction", "list", "--project", project, "--endpoint", dead).assertIs(10, NONE, List.of("PREDICTION_ERROR=null"));
        run("prediction", "show", "--project", project, "--endpoint", dead, "--prediction", prediction).assertIs(10, NONE, List.of("PREDICTION_ERROR=null"));
    }

    @Test
    void uninitializedProjectFailsWithTheCommandErrorLine() throws Exception {
        String empty = Files.createDirectories(tmp.resolve("empty")).toString();
        String prediction = UUID.randomUUID().toString();
        String missing = "No initialized Synesis project was found at the requested path";
        run(withProject(empty, join(task("task", "create"), "--title", "T", "--capability", "c"))).assertIs(10, NONE, List.of("TASK_ERROR=" + missing));
        run(withProject(empty, join(task("task", "claim"), "--task", TASK.toString()))).assertIs(10, NONE, List.of("TASK_ERROR=" + missing));
        run(withProject(empty, ownership(TASK.toString(), "ownership", "claim"))).assertIs(10, NONE, List.of("OWNERSHIP_ERROR=" + missing));
        run(withProject(empty, ownership(TASK.toString(), "ownership", "release"))).assertIs(10, NONE, List.of("OWNERSHIP_ERROR=" + missing));
        run(withProject(empty, predictionCreate())).assertIs(10, NONE, List.of("PREDICTION_ERROR=" + missing));
        run(withProject(empty, join(task("prediction", "respond"), "--prediction", prediction, "--action", "exact"))).assertIs(10, NONE, List.of("PREDICTION_ERROR=" + missing));
        run(withProject(empty, join(task("prediction", "publish"), "--prediction", prediction, "--stage", "patch-ready", "--commit", "c"))).assertIs(10, NONE, List.of("PREDICTION_ERROR=" + missing));
        run("speculation", "prepare", "--project", empty, "--prediction", prediction, "--base-commit", "HEAD").assertIs(10, NONE, List.of("SPECULATION_ERROR=" + missing));
        run("speculation", "validate", "--project", empty, "--endpoint", endpoint, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("SPECULATION_ERROR=" + missing));
        run("speculation", "retire", "--project", empty, "--endpoint", endpoint, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("SPECULATION_ERROR=" + missing));
        run("speculation", "invalidate", "--project", empty, "--endpoint", endpoint, "--profile", profile, "--prediction", prediction).assertIs(10, NONE, List.of("SPECULATION_ERROR=" + missing));
        run("task", "show", "--project", empty, "--endpoint", endpoint, "--task", TASK.toString()).assertIs(10, NONE, List.of("TASK_ERROR=" + missing));
        run("prediction", "list", "--project", empty, "--endpoint", endpoint).assertIs(10, NONE, List.of("PREDICTION_ERROR=" + missing));
        run("prediction", "show", "--project", empty, "--endpoint", endpoint, "--prediction", prediction).assertIs(10, NONE, List.of("PREDICTION_ERROR=" + missing));
    }

    @Test
    void missingRequiredOptionsAreUsageErrors() {
        List<String> usage = List.of("Usage error in 'claim'. Run 'synesis help' for valid commands and options.");
        run("task", "claim", "--project", project).assertIs(2, NONE, usage);
        run("ownership", "claim", "--project", project).assertIs(2, NONE, usage);
        run("task", "claim", "--project", project, "--endpoint", endpoint, "--profile", profile, "--supervisor", "s", "--worker", "w", "--task", "not-a-uuid").assertIs(2, NONE, usage);
        assertEquals(2, run("task", "show").exit());
    }

    private String[] withEndpoint(String replacement, String[] command) {
        return replaceValue(command, "--endpoint", replacement);
    }

    private String[] withProject(String replacement, String[] command) {
        return replaceValue(command, "--project", replacement);
    }

    private static String[] replaceValue(String[] command, String option, String value) {
        String[] copy = command.clone();
        for (int i = 0; i < copy.length - 1; i++) {
            if (copy[i].equals(option)) {
                copy[i + 1] = value;
            }
        }
        return copy;
    }
}
