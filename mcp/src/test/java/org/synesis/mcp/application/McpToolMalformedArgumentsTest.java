package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.mcp.contract.McpToolCatalog;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipStore;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Characterizes what each MCP tool replies today to malformed arguments, the raw
 * {@code manual_attestation_required} frame, and the agreement between the advertised
 * coordination schema and the payload checks the handler enforces. These tests record current
 * behavior, including behavior that looks accidental; they are not a statement that it is right.
 *
 * <p>Arguments and expected bodies are written with {@code '} for {@code "}. Envelopes are compared
 * byte for byte.</p>
 */
class McpToolMalformedArgumentsTest {

    private static final String HANDLE = "req_abcdefghijkl";
    private static final AtomicInteger CONNECTIONS = new AtomicInteger();

    private static String previousUserHome;
    private static Path root;
    private static McpProtocolHandler bound;
    private static McpProtocolHandler unattested;

    @BeforeAll
    static void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-provider-home-").toString());
        root = Files.createTempDirectory("synesis-mcp-malformed-");
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Test User");
        GitProcessRunner.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Test Repo\n");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "Initial commit");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        McpProviderTestSupport.install(location, "codex");
        McpProviderTestSupport.install(location, "claude");
        new ProviderManualService().install("codex");
        bound = handler("codex");
        assertEquals("ready", innerResponse(call(bound, "ensure_session", "{}")).get("status"));
        unattested = handler("claude");
    }

    @AfterAll
    static void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    private static McpProtocolHandler handler(String provider) {
        return handler(provider, "conn-" + CONNECTIONS.incrementAndGet());
    }

    private static McpProtocolHandler handler(String provider, String connection) {
        return new McpProtocolHandler(new AgentSessionService(), root, provider, connection);
    }

    private static String q(String json) {
        return json.replace('\'', '"');
    }

    private static String call(McpProtocolHandler handler, String tool, String arguments) {
        return handler.handleMessage(q("{'jsonrpc':'2.0','id':7,'method':'tools/call','params':{'name':'" + tool + "','arguments':" + arguments + "}}"));
    }

    /**
     * Compares what an answer states. Every refusal also says what to do about it, and a malformed
     * argument what the parser found; {@link #everyRefusalSaysWhatToDoAboutIt} asserts those.
     */
    @SuppressWarnings("unchecked")
    private static void assertText(String expectedBody, String frame) {
        Map<String, Object> actual = new LinkedHashMap<>(innerResponse(frame));
        if (actual.get("result") instanceof Map<?, ?> result) {
            Map<String, Object> stated = new LinkedHashMap<>((Map<String, Object>) result);
            stated.remove("instruction");
            stated.remove("detail");
            if (stated.isEmpty()) {
                actual.remove("result");
            } else {
                actual.put("result", stated);
            }
        }
        assertEquals(ProviderJson.parse(q(expectedBody)), actual, frame);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResponse(String frame) {
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) ProviderJson.parse(frame)).get("result");
        return (Map<String, Object>) ProviderJson.parse((String) ((Map<String, Object>) ((List<?>) result.get("content")).getFirst()).get("text"));
    }

    @SuppressWarnings("unchecked")
    private static String error(String frame) {
        Map<String, Object> result = (Map<String, Object>) innerResponse(frame).get("result");
        return result == null ? "" : String.valueOf(result.get("error"));
    }

    private static String blocked(String reason) {
        return "{'status':'blocked','reason':'" + reason + "'}";
    }

    private static String denied(String key, String value, String nextAction) {
        return "{'status':'blocked','reason':'policy_denied','nextAction':'" + nextAction + "','result':{'" + key + "':'" + value + "'}}";
    }

    @Test
    void readFileIgnoresWrongTypedWindowArguments() {
        String baseline = call(bound, "read_file", "{'path':'README.md'}");
        assertEquals("completed", innerResponse(baseline).get("status"));
        for (String wrongTyped : List.of("'startLine':'1'", "'endLine':true", "'maxBytes':'9'")) {
            assertEquals(baseline, call(bound, "read_file", "{'path':'README.md'," + wrongTyped + "}"), wrongTyped);
        }
    }

    @Test
    void readFileRejectsAnInvalidImplementationRevision() {
        for (String revision : List.of("2147483648", "'1'", "0", "1.5")) {
            assertText(blocked("invalid_path"), call(bound, "read_file", "{'path':'README.md','capabilityRequestHandle':'" + HANDLE + "','implementationRevision':" + revision + "}"));
        }
    }

    private static String editRefused(int index) {
        return "{'status':'blocked','reason':'invalid_path','result':{'reason':'PATCH_EDIT_INVALID','editIndex':" + index + "}}";
    }

    /** An unreadable edit used to be dropped and the rest of the patch applied and reported as success. */
    @Test
    void applyPatchRefusesAnEditItCannotReadAndNamesIt() {
        assertText(editRefused(0), call(bound, "apply_patch", "{'path':'README.md','edits':['x']}"));
        assertText(editRefused(1), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':'a','replace':'b'},{'find':'only-find'}]}"));
        assertText(editRefused(0), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':5,'replace':'a','expectedOccurrences':1}]}"));
        assertText(editRefused(0), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':'a','replace':true,'expectedOccurrences':1}]}"));
    }

    @Test
    void applyPatchRefusesAnEditWhoseCountIsNotAPositiveWholeNumber() {
        assertText(editRefused(0), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':'a','replace':'b','expectedOccurrences':0}]}"));
        assertText(editRefused(0), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':'a','replace':'b','expectedOccurrences':'1'}]}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRefusalSaysWhatToDoAboutIt() {
        Map<String, Object> finish = (Map<String, Object>) innerResponse(call(bound, "finish_lane", "{'claimEpoch':0}")).get("result");
        assertTrue(String.valueOf(finish.get("instruction")).contains("changed nothing"), finish.toString());
        assertTrue(String.valueOf(finish.get("detail")).contains("claimEpoch"), "the parser's finding must reach the caller: " + finish);
        Map<String, Object> kind = (Map<String, Object>) innerResponse(call(bound, "request_coordination", "{'kind':'nope','payload':{}}")).get("result");
        assertTrue(((List<String>) kind.get("allowedKinds")).contains("contract_publish"), kind.toString());
        for (String removed : List.of("capability_request", "contract_proposal", "contract_request")) {
            assertFalse(((List<String>) kind.get("allowedKinds")).contains(removed), removed);
            assertEquals("UNKNOWN_COORDINATION_KIND", ((Map<String, Object>) innerResponse(call(bound, "request_coordination", "{'kind':'" + removed + "','payload':{}}")).get("result")).get("error"), removed);
        }
        assertTrue(kind.containsKey("instruction"), kind.toString());
        Map<String, Object> responseKind = (Map<String, Object>) innerResponse(call(bound, "respond_coordination", "{'kind':'nope','payload':{}}")).get("result");
        assertTrue(((List<String>) responseKind.get("allowedKinds")).contains("review_validation"), responseKind.toString());
        assertFalse(((List<String>) responseKind.get("allowedKinds")).contains("capability_response"), responseKind.toString());
        Map<String, Object> contract = (Map<String, Object>) innerResponse(call(bound, "request_coordination", "{'kind':'contract_publish','payload':{'paths':['a.py'],'contract':{'purpose':'p'}}}")).get("result");
        assertEquals("CAPABILITY_CONTRACT_INVALID", contract.get("error"), contract.toString());
        assertEquals(11, ((List<String>) contract.get("contractFields")).size(), contract.toString());
        assertTrue(String.valueOf(contract.get("detail")).contains("contract lacks"), contract.toString());
        String complete = "'purpose':'p','inputs':'i','output':'o','errorSemantics':'e','sideEffects':'s','invariants':'v','compatibility':'c','performance':'f','concurrency':'n'";
        Map<String, Object> wrongType = (Map<String, Object>) innerResponse(call(bound, "request_coordination",
                "{'kind':'contract_publish','payload':{'paths':['a.py'],'contract':{" + complete + ",'requiredBehavior':5,'acceptanceTests':['t']}}}")).get("result");
        assertEquals("requiredBehavior must be an array of strings", wrongType.get("detail"), wrongType.toString());
        // One sentence where a list is expected is read as a list of one, so this contract is not refused for its shape.
        Map<String, Object> sentence = innerResponse(call(bound, "request_coordination",
                "{'kind':'contract_publish','payload':{'paths':['a.py'],'contract':{" + complete + ",'requiredBehavior':'does it','acceptanceTests':'passes'}}}"));
        assertFalse(String.valueOf(sentence).contains("CAPABILITY_CONTRACT_INVALID"), sentence.toString());
        for (String ownerPath : List.of("'../outside.py'", "'C:/abs.py'", "5")) {
            Map<String, Object> owner = (Map<String, Object>) innerResponse(call(bound, "request_coordination",
                    "{'kind':'contract_bind','payload':{'capability':'c','ownerPath':" + ownerPath + ",'revision':1}}")).get("result");
            assertEquals("OWNER_PATH_MUST_BE_A_REPOSITORY_RELATIVE_FILE", owner.get("error"), owner.toString());
        }
        for (String path : List.of("'../outside.py'", "'C:/abs.py'", "5")) {
            Map<String, Object> covered = (Map<String, Object>) innerResponse(call(bound, "request_coordination",
                    "{'kind':'contract_publish','payload':{'paths':[" + path + "],'contract':{" + complete + ",'requiredBehavior':['b'],'acceptanceTests':['t']}}}")).get("result");
            assertEquals("CONTRACT_PATH_MUST_BE_REPOSITORY_RELATIVE", covered.get("error"), covered.toString());
        }
        Map<String, Object> noField = (Map<String, Object>) innerResponse(call(bound, "respond_coordination",
                "{'kind':'implementation_validation','payload':{'inboxItemId':'i','capabilityRequestHandle':'" + HANDLE + "','implementationRevision':1,'result':'revision_required','reason':'r'}}")).get("result");
        assertEquals("COORDINATION_RESPONSE_FIELD_REQUIRED:contractField", noField.get("error"), noField.toString());
        assertEquals(11, ((List<String>) noField.get("contractFields")).size(), noField.toString());
        Map<String, Object> status = (Map<String, Object>) innerResponse(call(bound, "respond_coordination",
                "{'kind':'coordination_response','payload':{'coordinationRequest':'00000000-0000-0000-0000-000000000001','coordinationStatus':'maybe'}}")).get("result");
        assertEquals("COORDINATION_FIELD_INVALID:coordinationStatus", status.get("error"), status.toString());
        assertTrue(((List<String>) status.get("allowedValues")).contains("ACCEPTED"), status.toString());
        Map<String, Object> group = (Map<String, Object>) innerResponse(call(handler("codex"), "ensure_session", "{'task':{'goal':'g','acceptance':'a','workGroupId':'not-a-uuid'}}")).get("result");
        assertTrue(String.valueOf(group.get("detail")).contains("workGroupId"), group.toString());
    }

    @Test
    void runCommandRejectsMalformedFlagsAndRevisions() {
        List<String> malformed = List.of(
                "'argv':'git'",
                "'argv':[]",
                "'argv':['git',5]",
                "'argv':['git','status'],'workingDirectory':5",
                "'argv':['git','status'],'timeoutSeconds':'5'",
                "'argv':['git','status'],'timeoutSeconds':1.5",
                "'argv':['git','status'],'timeoutSeconds':0",
                "'argv':['git','status'],'background':true",
                "'argv':['git','status'],'capabilityRequestHandle':'" + HANDLE + "','implementationRevision':2147483648",
                "'argv':['git','status'],'capabilityRequestHandle':'" + HANDLE + "','implementationRevision':0",
                "'argv':['git','status'],'capabilityRequestHandle':'" + HANDLE + "','implementationRevision':'1'");
        for (String arguments : malformed) {
            assertText(blocked("invalid_path"), call(bound, "run_command", "{" + arguments + "}"));
        }
    }

    @Test
    void runCommandRefusesACommandThatOnlySleepsWithoutRunningIt() {
        // An agent told to wait ran this for 590 s while its review request went unanswered (ADR-0099).
        long started = System.nanoTime();
        String frame = call(bound, "run_command", "{'argv':['python','-c','import time; time.sleep(590)'],'timeoutSeconds':620}");
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(30), "refused before anything ran");
        assertText("{'status':'blocked','reason':'policy_denied','result':{'reason':'SLEEP_COMMAND_REFUSED'}}", frame);
        assertTrue(String.valueOf(innerResponse(frame).get("result")).contains("end your turn"), frame);
    }

    @Test
    void finishLaneReportsInvalidArguments() {
        for (String arguments : List.of("{'outcome':5}", "{'outcome':'bogus'}", "{'intentId':'zz'}", "{'claimEpoch':0}", "{'summary':5}")) {
            assertText(denied("reason", "INVALID_FINISH_LANE_ARGUMENTS", "request_human_help"), call(bound, "finish_lane", arguments));
        }
    }

    @Test
    void cancelLaneReportsANonStringReasonAsInvalidArguments() {
        assertText(denied("reason", "INVALID_CANCEL_LANE_ARGUMENTS", "request_human_help"), call(bound, "cancel_lane", "{'reason':5}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getNextActionRejectsABadCompletionRequestAndJudgesAnIncompleteIntegrationCheck() {
        for (String completionRequested : List.of("'yes'", "1")) {
            assertText(denied("reason", "INVALID_COMPLETION_REQUEST", "request_human_help"), call(bound, "get_next_action", "{'completionRequested':" + completionRequested + "}"));
        }
        // Missing integrationCheck fields become the string "null" and are judged as a stale base, not rejected.
        Map<String, Object> empty = innerResponse(call(bound, "get_next_action", "{'integrationCheck':{}}"));
        assertEquals("blocked", empty.get("status"));
        assertEquals("integration_conflict", empty.get("reason"));
        assertEquals("request_human_help", empty.get("nextAction"));
        Map<String, Object> emptyResult = (Map<String, Object>) empty.get("result");
        assertEquals(Boolean.FALSE, emptyResult.get("accepted"));
        assertEquals(List.of("TESTS_FAILED"), emptyResult.get("failures"));
        Map<String, Object> headOnly = (Map<String, Object>) innerResponse(call(bound, "get_next_action", "{'integrationCheck':{'controlHead':'abc'}}")).get("result");
        assertEquals(List.of("STALE_BASE", "TESTS_FAILED"), headOnly.get("failures"));
    }

    @Test
    void publishCapabilityImplementationRejectsAMissingHandleAndInvalidEvidence() {
        assertText(blocked("invalid_path"), call(bound, "publish_capability_implementation", "{}"));
        assertText(denied("reason", "CAPABILITY_PUBLICATION_EVIDENCE_REQUIRED", "request_human_help"), call(bound, "publish_capability_implementation", "{'capabilityRequestHandle':'" + HANDLE + "'}"));
        assertText(denied("reason", "CAPABILITY_PUBLICATION_EVIDENCE_INVALID", "request_human_help"), call(bound, "publish_capability_implementation", "{'capabilityRequestHandle':'" + HANDLE + "','contractRevision':'x'}"));
    }

    @Test
    void requestCoordinationReportsMalformedKindsAndPayloads() {
        String help = "request_human_help";
        assertText(denied("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD", help), call(bound, "request_coordination", "{'payload':{}}"));
        assertText(denied("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD", help), call(bound, "request_coordination", "{'kind':'collaboration_status'}"));
        assertEquals("UNKNOWN_COORDINATION_KIND", ((Map<?, ?>) innerResponse(call(bound, "request_coordination", "{'kind':'nope','payload':{}}")).get("result")).get("error"));
        assertText("{'status':'blocked','reason':'policy_denied','nextAction':'" + help + "','result':{'error':'COORDINATION_FIELD_NOT_ALLOWED:x','allowedFields':[]}}",
                call(bound, "request_coordination", "{'kind':'collaboration_status','payload':{'x':1}}"));
        assertText("{'status':'blocked','reason':'policy_denied','nextAction':'" + help + "','result':{'error':'COORDINATION_FIELD_NOT_ALLOWED:goal','allowedFields':['intentId','selectors','proposal']}}",
                call(bound, "request_coordination", "{'kind':'scope_revision','payload':{'goal':'g'}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:ownerPath", help), call(bound, "request_coordination", "{'kind':'contract_bind','payload':{'capability':'c','revision':1}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:ownerPath", help), call(bound, "request_coordination", "{'kind':'contract_bind','payload':{'capability':'c','ownerPath':null,'revision':1}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:capability", help), call(bound, "request_coordination", "{'kind':'contract_bind','payload':{'capability':' ','ownerPath':'a.py','revision':1}}"));
        for (String revision : List.of("'x'", "0", "-1", "1.5")) {
            assertText(denied("error", "COORDINATION_FIELD_INVALID:revision", help),
                    call(bound, "request_coordination", "{'kind':'contract_bind','payload':{'capability':'c','ownerPath':'a.py','revision':" + revision + "}}"));
        }
        assertText("{'status':'blocked','reason':'policy_denied','nextAction':'" + help + "','result':{'error':'COORDINATION_FIELD_NOT_ALLOWED:goal','allowedFields':['capability','ownerPath','revision']}}",
                call(bound, "request_coordination", "{'kind':'contract_bind','payload':{'goal':'g'}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:need", help), call(bound, "request_coordination", "{'kind':'contract_change_request','payload':{'ownerPath':'a.py'}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:need", help), call(bound, "request_coordination", "{'kind':'contract_change_request','payload':{'ownerPath':'a.py','need':' '}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:paths", help), call(bound, "request_coordination", "{'kind':'contract_publish','payload':{'contract':{}}}"));
        assertText(denied("error", "COORDINATION_FIELD_INVALID:claimEpoch", help),
                call(bound, "request_coordination", "{'kind':'continuation','payload':{'intentId':'00000000-0000-0000-0000-000000000001','claimEpoch':'1'}}"));
    }

    @Test
    void respondCoordinationRejectsAMissingOrNullRequiredKeyAndAWrongTypedField() {
        assertText(denied("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD", "request_human_help"), call(bound, "respond_coordination", "{}"));
        assertText(denied("error", "COORDINATION_RESPONSE_FIELD_REQUIRED:changeRequest", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'contract_change_response','payload':{'decision':'accepted'}}"));
        assertText(denied("error", "COORDINATION_RESPONSE_FIELD_REQUIRED:changeRequest", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'contract_change_response','payload':{'changeRequest':null,'decision':'accepted'}}"));
        assertText(denied("error", "COORDINATION_FIELD_INVALID:changeRequest", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'contract_change_response','payload':{'changeRequest':5,'decision':'accepted'}}"));
        assertText(denied("error", "COORDINATION_RESPONSE_REASON_REQUIRED", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'contract_change_response','payload':{'changeRequest':'00000000-0000-0000-0000-000000000001','decision':'declined'}}"));
        assertEquals("COORDINATION_RESPONSE_FIELD_INVALID:decision", ((Map<?, ?>) innerResponse(call(bound, "respond_coordination",
                "{'kind':'contract_change_response','payload':{'changeRequest':'00000000-0000-0000-0000-000000000001','decision':'maybe'}}")).get("result")).get("error"));
        assertEquals("UNKNOWN_COORDINATION_RESPONSE_KIND", ((Map<?, ?>) innerResponse(call(bound, "respond_coordination", "{'kind':'capability_response','payload':{}}")).get("result")).get("error"));
        for (String field : List.of("", ",'contractField':'nope'")) {
            assertEquals("COORDINATION_RESPONSE_FIELD_REQUIRED:contractField", ((Map<?, ?>) innerResponse(call(bound, "respond_coordination",
                    "{'kind':'implementation_validation','payload':{'inboxItemId':'i','capabilityRequestHandle':'" + HANDLE + "','implementationRevision':1,'result':'revision_required','reason':'r'" + field + "}}")).get("result")).get("error"), field);
        }
        assertText(denied("error", "COORDINATION_FIELD_INVALID:coordinationRequest", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'coordination_response','payload':{'coordinationRequest':5,'coordinationStatus':'ACCEPTED'}}"));
        assertText(denied("error", "COORDINATION_FIELD_INVALID:implementationRevision", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'implementation_validation','payload':{'inboxItemId':'i','capabilityRequestHandle':'" + HANDLE + "','implementationRevision':'1','result':'inspect'}}"));
    }

    @Test
    void ensureSessionRejectsFlattenedTaskFieldsAndMalformedReviewTargets() {
        assertText(denied("reason", "TASK_FIELDS_MUST_BE_NESTED", "request_human_help"),
                call(handler("codex"), "ensure_session", "{'task':{'goal':'g','acceptance':'a'},'reviewTargets':[{'path':'a.txt'}]}"));
        for (String targets : List.of("[5]", "[{'kind':'path_exact'}]", "[{'path':'a.txt','kind':'zzz'}]", "[{'path':'a.txt','kind':5}]", "{}")) {
            assertText(denied("reason", "INVALID_TASK_INTENT", "request_human_help"),
                    call(handler("codex"), "ensure_session", "{'task':{'goal':'g','acceptance':'a','reviewTargets':" + targets + "}}"));
        }
    }

    /**
     * A claim that cannot be read is refused: dropped, the lane was announced with less than the
     * agent believed it held. An unknown kind is still read as an exact path.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ensureSessionRefusesAClaimItCannotReadAndDefaultsAnUnknownKind() {
        for (String entry : List.of("[5]", "[{'kind':'path_exact'}]", "{}", "[{'path':'/etc/passwd'}]")) {
            Map<String, Object> reply = innerResponse(call(handler("codex"), "ensure_session", "{'task':{'goal':'g','acceptance':'a','claims':" + entry + "}}"));
            assertEquals("blocked", reply.get("status"), entry + " " + reply);
            assertEquals("INVALID_CLAIM", ((Map<String, Object>) reply.get("result")).get("reason"), entry + " " + reply);
        }
        for (String entry : List.of("[{'path':'claim-a.txt','kind':'zzz'}]", "[{'path':'claim-b.txt','kind':5}]")) {
            Map<String, Object> reply = innerResponse(call(handler("codex"), "ensure_session", "{'task':{'goal':'g','acceptance':'a','claims':" + entry + "}}"));
            assertEquals("ready", reply.get("status"), entry);
        }
    }

    /**
     * A reviewer with no producer lane in its work group is refused and told what to do, and the refusal does not
     * spend the session's one lane: the same session can then announce a producer lane.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ensureSessionRefusesAReviewerWithoutAProducerAndLeavesTheSessionItsLane() {
        McpProtocolHandler handler = handler("codex");
        Map<String, Object> refused = innerResponse(call(handler, "ensure_session", "{'task':{'role':'reviewer','goal':'g','acceptance':'a','claims':[{'path':'review-without-producer.md'}]}}"));
        assertEquals("blocked", refused.get("status"), refused.toString());
        assertEquals("policy_denied", refused.get("reason"), refused.toString());
        assertEquals("ensure_session", refused.get("nextAction"), refused.toString());
        Map<String, Object> result = (Map<String, Object>) refused.get("result");
        assertEquals("REVIEWER_REQUIRES_PRODUCER", result.get("reason"), refused.toString());
        assertFalse(String.valueOf(result.get("instruction")).isBlank(), refused.toString());

        Map<String, Object> admitted = innerResponse(call(handler, "ensure_session", "{'task':{'goal':'g','acceptance':'a','claims':[{'path':'producer-after-refusal.txt'}]}}"));
        assertEquals("ready", admitted.get("status"), admitted.toString());
    }

    @Test
    void aMalformedToolCallEnvelopeIsAnInvalidParamsErrorBeforeAnyOtherGate() {
        McpProtocolHandler handler = handler("codex");
        assertEquals(q("{'jsonrpc':'2.0','id':7,'error':{'code':-32602,'message':'Invalid params: missing params object'}}"),
                handler.handleMessage(q("{'jsonrpc':'2.0','id':7,'method':'tools/call'}")));
        assertEquals(q("{'jsonrpc':'2.0','id':7,'error':{'code':-32602,'message':'raw MCP tool name required'}}"),
                handler.handleMessage(q("{'jsonrpc':'2.0','id':7,'method':'tools/call','params':{'name':'synesis.read_file','arguments':{}}}")));
        assertEquals(q("{'jsonrpc':'2.0','id':7,'error':{'code':-32602,'message':'tool arguments must be an object'}}"),
                handler.handleMessage(q("{'jsonrpc':'2.0','id':7,'method':'tools/call','params':{'name':'read_file','arguments':5}}")));
    }

    @Test
    void anInvalidPinnedRootStopsEveryCallBeforeAdmissionAttestationAndDispatch() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), Files.createTempDirectory("synesis-mcp-invalid-pin-"), "claude", "conn-" + CONNECTIONS.incrementAndGet(), true);
        String refusal = "{'status':'retry_required','reason':'workspace_not_ready','nextAction':'ensure_session','result':{'error':'PROJECT_ROOT_PIN_INVALID'}}";
        for (String tool : List.of("bogus", "apply_patch", "ensure_session")) {
            assertText(refusal, call(handler, tool, "{}"));
        }
    }

    /** Issues a managed attachment for a fresh codex binding and returns the handler that carries it. */
    private static McpProtocolHandler managedHandler(String connection, boolean completed) throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(root);
        var binding = new ProviderSessionBindingService().ensure(location, "codex", connection).binding();
        var ownership = ProviderThreadOwnershipStore.storeFor(location).acquire(location.projectId().toString(), "codex", connection + "-thread", binding.sessionId());
        var store = ManagedAttachmentService.storeFor(location, binding.sessionId());
        var issued = new ManagedAttachmentService().issueFromOwnership(location, location.projectId().toString(), "codex", binding.sessionId(), "normal-provider-home", ownership, store);
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), root, "codex", connection, new SessionProcessIdentity(1L, "test", "test", 1L, "nonce"), true, issued.record());
        if (completed) {
            new ManagedAttachmentService().activate(store, 1L);
            assertTrue(new ProviderSessionBindingService().complete(location, "codex", connection));
        }
        return handler;
    }

    private static void assertManagedRefusal(String reason, String frame) {
        String body = "{\"status\":\"blocked\",\"reason\":\"" + reason + "\"}";
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + body.replace("\"", "\\\"") + "\"}],\"isError\":true}}", frame);
    }

    @Test
    void aPendingManagedConnectionIsQuarantinedBeforeAttestationAndDispatch() throws Exception {
        McpProtocolHandler handler = managedHandler("conn-managed-pending", false);
        for (String tool : List.of("bogus", "apply_patch", "get_next_action")) {
            assertManagedRefusal("managed_attachment_pending", call(handler, tool, "{}"));
        }
    }

    @Test
    void aCompletedManagedConnectionIsRejectedOutsideTheReviewTools() throws Exception {
        McpProtocolHandler handler = managedHandler("conn-managed-completed", true);
        for (String tool : List.of("ensure_session", "apply_patch", "publish_capability_implementation", "finish_lane", "cancel_lane", "bogus")) {
            assertManagedRefusal("managed_attachment_rejected", call(handler, tool, "{}"));
        }
        // The reads and test runs of a review it holds reach the review snapshot service, which alone
        // accepts a completed binding (McpProducersReviewEachOtherTest).
        for (String tool : List.of("get_next_action", "request_coordination", "respond_coordination", "read_file", "run_command")) {
            assertFalse(call(handler, tool, "{}").contains("managed_attachment_"), tool);
        }
    }

    @Test
    void aProoflessConnectionOnAManagedBindingNeedsItsProof() throws Exception {
        managedHandler("conn-managed-proofless", false);
        McpProtocolHandler proofless = new McpProtocolHandler(new AgentSessionService(), root.getParent(), "codex", "conn-managed-proofless", false);
        proofless.handleMessage(q("{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'rootUri':'" + root.toUri() + "'}}"));
        for (String tool : List.of("ensure_session", "bogus")) {
            assertManagedRefusal("managed_attachment_proof_required", call(proofless, tool, "{}"));
        }
    }

    @Test
    void aFencedRecoveryLaneIsStoppedBeforeAttestationAndDispatch() throws Exception {
        String connection = "conn-fenced-" + CONNECTIONS.incrementAndGet();
        McpProtocolHandler handler = handler("codex", connection);
        assertEquals("ready", innerResponse(call(handler, "ensure_session", "{}")).get("status"));
        var binding = new ProviderSessionBindingService().find(new ProjectApplicationService().locate(root), "codex", connection).orElseThrow();
        Files.writeString(Path.of(binding.worktreePath()).resolve("unfinished.txt"), "keep this work\n");
        new WorkspaceCollaborationService().announce(root, "codex", connection, "Implement the task", "Preserve unfinished work on disconnect", List.of(ResourceSelector.pathExact("unfinished.txt")));
        handler.close();
        for (String tool : List.of("apply_patch", "get_next_action", "bogus")) {
            assertText(denied("reason", "SESSION_EPOCH_FENCED", "request_human_help"), call(handler, tool, "{}"));
        }
    }

    @Test
    void aBoundConnectionWhoseRecoveryStateIsUnreadableFailsClosedExceptForTheClosingInboxRead() throws Exception {
        String connection = "conn-completed-" + CONNECTIONS.incrementAndGet();
        McpProtocolHandler handler = handler("codex", connection);
        assertEquals("ready", innerResponse(call(handler, "ensure_session", "{}")).get("status"));
        assertTrue(new ProviderSessionBindingService().complete(new ProjectApplicationService().locate(root), "codex", connection));
        for (String tool : List.of("apply_patch", "finish_lane", "bogus")) {
            assertText(denied("reason", "SESSION_RECOVERY_STATE_UNAVAILABLE", "request_human_help"), call(handler, tool, "{}"));
        }
        // Reads and commands pass the fence for the review a completed lane may hold; holding none,
        // the lane has no workspace left and is sent to start a fresh session.
        assertText("{'status':'retry_required','reason':'workspace_stale','nextAction':'ensure_session'}", call(handler, "read_file", "{'path':'README.md'}"));
        assertText("{'status':'retry_required','reason':'workspace_stale','nextAction':'ensure_session'}", call(handler, "run_command", "{'argv':['git','status']}"));
        Map<String, Object> closingRead = innerResponse(call(handler, "get_next_action", "{}"));
        assertEquals("completed", closingRead.get("status"));
        assertEquals("COMPLETED", ((Map<?, ?>) closingRead.get("result")).get("state"));
    }

    @Test
    void anUnknownToolIsAToolErrorNamingTheInternalPrefix() {
        assertEquals(q("{'jsonrpc':'2.0','id':7,'result':{'content':[{'type':'text','text':'Unknown tool: synesis.bogus'}],'isError':true}}"), call(bound, "bogus", "{}"));
    }

    /**
     * Records current behavior: the predicate reads legacy top-level keys, so every request_coordination
     * call, collaboration_status included, needs a valid manual attestation.
     */
    @Test
    void anUnattestedProviderIsStoppedBeforeDispatch() {
        String frame = "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\",\"details\":{\"manual\":\"MANUAL_MISSING\",\"authorityReductionAllowed\":true}}";
        assertText(frame.replace('"', '\''), call(unattested, "apply_patch", "{'path':'README.md','create':true,'content':'x'}"));
        assertText(frame.replace('"', '\''), call(unattested, "request_coordination", "{'kind':'collaboration_status','payload':{}}"));
        // A key outside the schema must not exempt a request: the normalizer ignores it and would still run the operation.
        assertText(frame.replace('"', '\''), call(unattested, "request_coordination", "{'kind':'collaboration_status','payload':{},'collaborationOperation':'status'}"));
        assertText(frame.replace('"', '\''), call(unattested, "request_coordination", "{'kind':'collaboration_status','payload':{},'coordinationRequest':'status'}"));
        assertText("{'status':'blocked','reason':'manual_attestation_required','claims':'REJECTED'}",
                call(unattested, "ensure_session", "{'task':{'goal':'g','acceptance':'a','claims':[{'path':'unattested.txt'}]}}"));
    }

    @Test
    void thePayloadChecksAgreeWithTheAdvertisedSchemaForTheKindsBothDefine() {
        checkAgainstSchema(McpToolCatalog.REQUEST_COORDINATION, "COORDINATION_", Set.of("contract_publish", "contract_bind", "contract_change_request"));
        checkAgainstSchema(McpToolCatalog.RESPOND_COORDINATION, "COORDINATION_RESPONSE_", Set.of("contract_change_response", "coordination_response", "implementation_validation"));
    }

    @SuppressWarnings("unchecked")
    private static void checkAgainstSchema(String tool, String prefix, Set<String> expectedKinds) {
        Map<String, Object> schema = McpToolCatalog.toolsList().stream().filter(entry -> tool.equals(entry.get("name"))).map(entry -> (Map<String, Object>) entry.get("inputSchema")).findFirst().orElseThrow();
        Set<String> everyField = ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("payload")).get("properties")).keySet();
        Set<String> constrainedKinds = new LinkedHashSet<>();
        for (Object alternative : (List<?>) schema.get("oneOf")) {
            Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) alternative).get("properties");
            Map<String, Object> payload = (Map<String, Object>) properties.get("payload");
            if (!Boolean.FALSE.equals(payload.get("additionalProperties"))) {
                continue;
            }
            String kind = (String) ((List<?>) ((Map<String, Object>) properties.get("kind")).get("enum")).getFirst();
            constrainedKinds.add(kind);
            Set<String> allowed = ((Map<String, Object>) payload.get("properties")).keySet();
            List<String> required = (List<String>) payload.get("required");
            String requiredError = prefix + "FIELD_REQUIRED:";
            String notAllowedError = prefix + "FIELD_NOT_ALLOWED:";
            for (String omitted : required) {
                Map<String, Object> others = new LinkedHashMap<>();
                required.stream().filter(field -> !field.equals(omitted)).forEach(field -> others.put(field, "x"));
                assertEquals(requiredError + omitted, error(callWith(tool, kind, others)), kind + " without " + omitted);
            }
            Map<String, Object> everyRequired = new LinkedHashMap<>();
            required.forEach(field -> everyRequired.put(field, "x"));
            assertFalse(error(callWith(tool, kind, everyRequired)).startsWith(requiredError), kind + " with every advertised required field");
            for (String field : everyField) {
                String reply = error(callWith(tool, kind, Map.of(field, "x")));
                assertEquals(allowed.contains(field), !reply.startsWith(notAllowedError), kind + " field " + field);
            }
        }
        assertEquals(expectedKinds, constrainedKinds);
    }

    private static String callWith(String tool, String kind, Map<String, Object> payload) {
        List<String> entries = new ArrayList<>();
        payload.forEach((key, value) -> entries.add("'" + key + "':'" + value + "'"));
        return call(bound, tool, "{'kind':'" + kind + "','payload':{" + String.join(",", entries) + "}}");
    }
}
