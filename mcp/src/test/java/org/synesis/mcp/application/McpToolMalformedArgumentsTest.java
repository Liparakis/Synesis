package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

import org.synesis.mcp.contract.McpToolCatalog;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
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
        return new McpProtocolHandler(new AgentSessionService(), root, provider, "conn-" + CONNECTIONS.incrementAndGet());
    }

    private static String q(String json) {
        return json.replace('\'', '"');
    }

    private static String call(McpProtocolHandler handler, String tool, String arguments) {
        return handler.handleMessage(q("{'jsonrpc':'2.0','id':7,'method':'tools/call','params':{'name':'" + tool + "','arguments':" + arguments + "}}"));
    }

    /** Asserts the frame is exactly the text envelope around this body. */
    private static void assertText(String expectedBody, String frame) {
        String escaped = q(expectedBody).replace("\"", "\\\"");
        assertEquals(q("{'jsonrpc':'2.0','id':7,'result':{'content':[{'type':'text','text':'" + escaped + "'}]}}"), frame);
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

    @Test
    void applyPatchSilentlyDropsAnEditThatIsNotAnObject() {
        assertText("{'status':'retry_required','reason':'patch_precondition_required','nextAction':'retry'}", call(bound, "apply_patch", "{'path':'README.md','edits':['x']}"));
    }

    @Test
    void applyPatchRejectsAnEditWhoseFindOrReplaceIsNotAString() {
        assertText(blocked("invalid_path"), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':5,'replace':'a','expectedOccurrences':1}]}"));
        assertText(blocked("invalid_path"), call(bound, "apply_patch", "{'path':'README.md','edits':[{'find':'a','replace':true,'expectedOccurrences':1}]}"));
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
        assertText(denied("error", "UNKNOWN_COORDINATION_KIND", help), call(bound, "request_coordination", "{'kind':'nope','payload':{}}"));
        assertText(denied("error", "COORDINATION_FIELD_NOT_ALLOWED:x", help), call(bound, "request_coordination", "{'kind':'collaboration_status','payload':{'x':1}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:conflictingIntentId", help), call(bound, "request_coordination", "{'kind':'contract_request','payload':{'proposal':'p'}}"));
        assertText(denied("error", "COORDINATION_FIELD_REQUIRED:conflictingIntentId", help), call(bound, "request_coordination", "{'kind':'contract_request','payload':{'conflictingIntentId':null,'proposal':'p'}}"));
        assertText(denied("error", "COORDINATION_FIELD_INVALID:conflictingIntentId", help), call(bound, "request_coordination", "{'kind':'contract_request','payload':{'conflictingIntentId':5,'proposal':'p'}}"));
        assertText(denied("error", "COORDINATION_FIELD_INVALID:conflictingIntentId", help), call(bound, "request_coordination", "{'kind':'contract_request','payload':{'conflictingIntentId':'x','proposal':'p'}}"));
        assertText(denied("error", "COORDINATION_FIELD_INVALID:claimEpoch", help),
                call(bound, "request_coordination", "{'kind':'continuation','payload':{'intentId':'00000000-0000-0000-0000-000000000001','claimEpoch':'1'}}"));
    }

    @Test
    void respondCoordinationRejectsAMissingOrNullRequiredKeyAndAWrongTypedField() {
        assertText(denied("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD", "request_human_help"), call(bound, "respond_coordination", "{}"));
        assertText(denied("error", "COORDINATION_RESPONSE_FIELD_REQUIRED:capabilityRequestHandle", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'capability_response','payload':{'response':'accept','contractRevision':1}}"));
        assertText(denied("error", "COORDINATION_RESPONSE_FIELD_REQUIRED:capabilityRequestHandle", "request_human_help"),
                call(bound, "respond_coordination", "{'kind':'capability_response','payload':{'capabilityRequestHandle':null,'response':'accept','contractRevision':1}}"));
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

    /** Records current behavior: unlike reviewTargets, a malformed claims entry is dropped or defaulted and admission still succeeds. */
    @Test
    void ensureSessionDropsOrDefaultsMalformedClaimEntries() {
        List<String> claims = List.of("[5]", "[{'kind':'path_exact'}]", "[{'path':'claim-a.txt','kind':'zzz'}]", "[{'path':'claim-b.txt','kind':5}]");
        for (String entry : claims) {
            Map<String, Object> reply = innerResponse(call(handler("codex"), "ensure_session", "{'task':{'goal':'g','acceptance':'a','claims':" + entry + "}}"));
            assertEquals("ready", reply.get("status"), entry);
        }
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
        assertText("{'status':'blocked','reason':'manual_attestation_required','claims':'REJECTED'}",
                call(unattested, "ensure_session", "{'task':{'goal':'g','acceptance':'a','claims':[{'path':'unattested.txt'}]}}"));
    }

    @Test
    void thePayloadChecksAgreeWithTheAdvertisedSchemaForTheKindsBothDefine() {
        checkAgainstSchema(McpToolCatalog.REQUEST_COORDINATION, "COORDINATION_", Set.of("capability_request", "contract_request"));
        checkAgainstSchema(McpToolCatalog.RESPOND_COORDINATION, "COORDINATION_RESPONSE_", Set.of("capability_response", "coordination_response", "implementation_validation"));
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
