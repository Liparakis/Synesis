package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Producers of a project with mandatory review and no reviewer review one another while their own
 * snapshots wait (ADR-0089, amended 2026-10-17): the run recorded as "Two homes, bigger goal" in
 * {@code docs/agent/CURRENT.md}, where four writers each held a wait for their own review.
 */
final class McpProducersReviewEachOtherTest {

    private static final List<String> MODULES = List.of("slug.py", "wrap.py", "roman.py", "csvline.py");

    @Test
    @SuppressWarnings("unchecked")
    void fourWaitingProducersReviewEveryOtherSnapshotAndNeverTheirOwn(@TempDir Path temp) throws Exception {
        Path project = mandatoryReviewProject(temp.resolve("producers-review-each-other"));
        List<Producer> producers = new ArrayList<>();
        for (int index = 0; index < MODULES.size(); index++) {
            producers.add(producer(project, "producer-" + index, MODULES.get(index)));
        }
        List<String> snapshots = new ArrayList<>();
        for (Producer producer : producers) {
            snapshots.add(publish(producer));
        }

        Producer first = producers.getFirst();
        Map<String, Object> offered = result(first.handler().handleMessage(toolCall("get_next_action", Map.of())));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) offered.get("actionableReviewCandidates");
        assertEquals("request_coordination", offered.get("nextProtocolAction"), offered.toString());
        assertEquals(Set.copyOf(snapshots.subList(1, 4)), candidates.stream().map(candidate -> String.valueOf(candidate.get("snapshotId"))).collect(Collectors.toSet()), offered.toString());
        Map<String, Object> ownSnapshot = Map.of("kind", "work_group_join", "payload",
                Map.of("workGroupId", offered.get("workGroupId"), "intentId", first.intentId(), "snapshotId", snapshots.getFirst(), "proposal", "Review my own module"));
        String selfReview = first.handler().handleMessage(toolCall("request_coordination", ownSnapshot));
        assertTrue(selfReview.contains("REVIEW_SELF_NOT_ALLOWED"), selfReview);

        // Each producer prefers the next one's snapshot, so the reviews form a ring rather than
        // all landing on the first candidate; the choice stays the producer's.
        boolean progressed = true;
        for (int round = 0; round < 30 && progressed; round++) {
            progressed = false;
            for (int index = 0; index < producers.size(); index++) {
                progressed |= step(producers.get(index), producers.get((index + 1) % producers.size()).participant());
            }
        }

        PredictionEventStore store = store(project);
        Set<String> producerParticipants = producers.stream().map(Producer::participant).collect(Collectors.toSet());
        for (String snapshot : snapshots) {
            ReviewValidationPayload decision = store.workGroupProjection().reviewValidationForSnapshot(snapshot).orElseThrow(() -> new AssertionError("no review of " + snapshot));
            assertEquals("ACCEPTED", decision.result(), snapshot);
            assertTrue(producerParticipants.contains(decision.targetParticipant()), decision.toString());
            assertNotEquals(decision.sourceParticipant(), decision.targetParticipant(), decision.toString());
        }
        // Review was taken through grants; no producer announced a second, reviewer, lane.
        assertEquals(MODULES.size(), store.events().stream().filter(event -> event.type() == PredictionEventType.WORK_INTENT_ANNOUNCED).count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProducerWaitingForItsOwnReviewRequestIsStillShownTheAdmissionAskedOfIt(@TempDir Path temp) throws Exception {
        Path project = mandatoryReviewProject(temp.resolve("admission-not-hidden"));
        List<Producer> producers = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            producers.add(producer(project, "admission-" + index, MODULES.get(index)));
            snapshots.add(publish(producers.get(index)));
        }
        Producer slug = producers.get(0);
        Producer wrap = producers.get(1);
        Producer roman = producers.get(2);
        String workGroupId = String.valueOf(store(project).collaborationProjection().intent(java.util.UUID.fromString(slug.intentId())).orElseThrow().workGroupId());
        answered(slug.handler().handleMessage(toolCall("request_coordination", Map.of("kind", "work_group_join", "payload",
                Map.of("workGroupId", workGroupId, "intentId", wrap.intentId(), "snapshotId", snapshots.get(1), "proposal", "Review wrap.py")))));
        answered(roman.handler().handleMessage(toolCall("request_coordination", Map.of("kind", "work_group_join", "payload",
                Map.of("workGroupId", workGroupId, "intentId", slug.intentId(), "snapshotId", snapshots.get(0), "proposal", "Review slug.py")))));

        Map<String, Object> next = result(slug.handler().handleMessage(toolCall("get_next_action", Map.of())));
        assertEquals("respond_coordination", next.get("nextAction"), next.toString());
        Map<String, Object> context = (Map<String, Object>) next.get("nextProtocolContext");
        assertEquals(roman.participant(), context.get("requester"), next.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOwnerAskedTwiceForOneSnapshotAdmitsOneAndIsToldToRejectTheOther(@TempDir Path temp) throws Exception {
        Path project = mandatoryReviewProject(temp.resolve("one-snapshot-two-requests"));
        List<Producer> producers = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            producers.add(producer(project, "twice-" + index, MODULES.get(index)));
            snapshots.add(publish(producers.get(index)));
        }
        Producer roman = producers.get(2);
        String workGroupId = String.valueOf(store(project).collaborationProjection().intent(java.util.UUID.fromString(roman.intentId())).orElseThrow().workGroupId());
        Map<String, Object> reviewRoman = Map.of("kind", "work_group_join", "payload",
                Map.of("workGroupId", workGroupId, "intentId", roman.intentId(), "snapshotId", snapshots.get(2), "proposal", "Review roman.py"));
        answered(producers.get(0).handler().handleMessage(toolCall("request_coordination", reviewRoman)));
        answered(producers.get(1).handler().handleMessage(toolCall("request_coordination", reviewRoman)));

        assertEquals("ACCEPTED", ownerAnswer(roman).get("coordinationStatus"));
        Map<String, Object> second = ownerAnswer(roman);
        assertEquals("REJECTED", second.get("coordinationStatus"), second.toString());
        assertTrue(String.valueOf(second.get("proposal")).contains("REVIEW_CANDIDATE_ALREADY_ASSIGNED"), second.toString());

        Map<String, Object> loser = result(producers.get(1).handler().handleMessage(toolCall("get_next_action", Map.of())));
        assertFalse(Boolean.TRUE.equals(loser.get("reviewRequestPending")), loser.toString());
        assertEquals(1, store(project).workGroupProjection().grants().size());
    }

    /**
     * Real run across two computers on the ADR-0099 amendment: an agent held a review grant for
     * roman.py, its own csvline.py was integrated meanwhile, and its review decision was refused at
     * the recovery fence (SESSION_RECOVERY_STATE_UNAVAILABLE). Nobody else could review roman.py,
     * since the grant held it, and the run stopped. A completed lane may still finish a review it
     * holds. Real run s5-run6 (PC and VM, Link down) then stopped one step earlier: the reads and
     * test runs of that review met the same fence, so the reviewer could not reach a verdict.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aProducerWhoseOwnModuleWasIntegratedStillReadsTestsAndFinishesTheReviewItHolds(@TempDir Path temp) throws Exception {
        Path project = mandatoryReviewProject(temp.resolve("finish-held-review"));
        List<Producer> producers = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            producers.add(producer(project, "held-review-" + index, MODULES.get(index)));
            snapshots.add(publish(producers.get(index)));
        }
        Producer slug = producers.get(0);
        Producer wrap = producers.get(1);
        Producer roman = producers.get(2);
        String workGroupId = String.valueOf(store(project).collaborationProjection().intent(java.util.UUID.fromString(slug.intentId())).orElseThrow().workGroupId());
        // A real agent binds its connection through ensure_session, which is what the recovery fence guards.
        answered(wrap.handler().handleMessage(toolCall("ensure_session", Map.of())));

        Map<String, Object> slugReview = reviewDecision(wrap, slug, snapshots.get(0), workGroupId);
        Map<String, Object> wrapReview = reviewDecision(roman, wrap, snapshots.get(1), workGroupId);
        wrapReview.put("result", "accepted");
        answered(roman.handler().handleMessage(toolCall("respond_coordination", Map.of("kind", "review_validation", "payload", wrapReview))));
        for (int look = 0; look < 3 && !"COMPLETED".equals(store(project).collaborationProjection().participantState(wrap.participant()).orElseThrow().name()); look++) {
            // The author's next look integrates its accepted snapshot, as a real agent's does.
            wrap.handler().handleMessage(toolCall("get_next_action", Map.of("completionRequested", true)));
        }
        assertEquals("COMPLETED", store(project).collaborationProjection().participantState(wrap.participant()).orElseThrow().name(), "wrap.py was integrated");

        Map<String, Object> read = result(wrap.handler().handleMessage(toolCall("read_file", Map.of("path", "slug.py"))));
        assertEquals("immutable_review_snapshot", read.get("workspace"), read.toString());
        assertEquals(snapshots.get(0), read.get("reviewSnapshotId"), read.toString());
        Map<String, Object> run = result(wrap.handler().handleMessage(toolCall("run_command", Map.of("argv", List.of("git", "rev-parse", "HEAD")))));
        assertEquals("immutable_review_snapshot", run.get("workspace"), run.toString());
        assertEquals(snapshots.get(0), run.get("reviewSnapshotId"), run.toString());
        // The completed lane may only review: nothing it writes is admitted.
        String patch = wrap.handler().handleMessage(toolCall("apply_patch", Map.of("path", "wrap.py", "create", true, "content", "x\n")));
        assertTrue(patch.contains("SESSION_RECOVERY_STATE_UNAVAILABLE"), patch);

        slugReview.put("result", "accepted");
        String decided = wrap.handler().handleMessage(toolCall("respond_coordination", Map.of("kind", "review_validation", "payload", slugReview)));
        assertFalse(decided.contains("SESSION_RECOVERY_STATE_UNAVAILABLE"), decided);
        assertEquals("ACCEPTED", store(project).workGroupProjection().reviewValidationForSnapshot(snapshots.get(0)).orElseThrow().result());
        // With its verdict recorded the completed lane holds no review, and no workspace is read for it.
        Map<String, Object> afterVerdict = result(wrap.handler().handleMessage(toolCall("read_file", Map.of("path", "slug.py"))));
        assertFalse(afterVerdict.containsKey("content"), afterVerdict.toString());
        assertEquals("ensure_session", afterVerdict.get("nextAction"), afterVerdict.toString());
    }

    /**
     * Real run s5-run6, the other half: the reviewer whose own module was integrated stopped without a verdict, its
     * grant stayed consumed by a session that was gone, and nobody else was offered the review. Its computer judges the
     * session gone once its connection closed, releases the review, and another producer that is not the author
     * reviews it (ADR-0098 designer item 2).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aReviewWhoseReviewerIsGoneIsOfferedToAnotherNonAuthor(@TempDir Path temp) throws Exception {
        Path project = mandatoryReviewProject(temp.resolve("gone-reviewer"));
        List<Producer> producers = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            producers.add(producer(project, "gone-reviewer-" + index, MODULES.get(index)));
            snapshots.add(publish(producers.get(index)));
        }
        Producer slug = producers.get(0);
        Producer wrap = producers.get(1);
        Producer roman = producers.get(2);
        String workGroupId = String.valueOf(store(project).collaborationProjection().intent(java.util.UUID.fromString(slug.intentId())).orElseThrow().workGroupId());
        answered(wrap.handler().handleMessage(toolCall("ensure_session", Map.of())));
        reviewDecision(wrap, slug, snapshots.get(0), workGroupId);
        Map<String, Object> wrapReview = reviewDecision(roman, wrap, snapshots.get(1), workGroupId);
        wrapReview.put("result", "accepted");
        answered(roman.handler().handleMessage(toolCall("respond_coordination", Map.of("kind", "review_validation", "payload", wrapReview))));
        for (int look = 0; look < 3 && !"COMPLETED".equals(store(project).collaborationProjection().participantState(wrap.participant()).orElseThrow().name()); look++) {
            wrap.handler().handleMessage(toolCall("get_next_action", Map.of("completionRequested", true)));
        }
        assertEquals("COMPLETED", store(project).collaborationProjection().participantState(wrap.participant()).orElseThrow().name(), "wrap.py was integrated");

        // The reviewer stops without a verdict and its connection ends.
        wrap.handler().close();
        // The server's merge driver runs a pass, as it does at an interval: it releases the review of the reviewer
        // whose session is gone, and the remaining non-author is offered it.
        ProjectLocation location = new ProjectApplicationService().locate(project);
        new IntegrationOrchestrationService().orchestrateIntegration(location.root(), location.openCoordinationStore(), location.loadOrCreateIdentity());
        Map<String, Object> offer = result(roman.handler().handleMessage(toolCall("get_next_action", Map.of())));
        assertTrue(offer.get("actionableReviewCandidates") instanceof List<?> candidates
                && candidates.stream().anyMatch(candidate -> snapshots.get(0).equals(((Map<String, Object>) candidate).get("snapshotId"))), offer.toString());
        Map<String, Object> slugReview = reviewDecision(roman, slug, snapshots.get(0), workGroupId);
        slugReview.put("result", "accepted");
        answered(roman.handler().handleMessage(toolCall("respond_coordination", Map.of("kind", "review_validation", "payload", slugReview))));

        var workGroups = store(project).workGroupProjection();
        var slugGrants = workGroups.grants().stream().filter(grant -> snapshots.get(0).equals(grant.snapshotId())).toList();
        assertTrue(slugGrants.stream().anyMatch(grant -> grant.targetParticipant().equals(wrap.participant()) && workGroups.grantRevoked(grant.identity())),
                "the gone reviewer's grant was released");
        assertEquals("ACCEPTED", workGroups.reviewValidationForSnapshot(snapshots.get(0)).orElseThrow().result());
        assertTrue(slugGrants.stream().anyMatch(grant -> grant.targetParticipant().equals(roman.participant())), "roman.py's producer reviewed slug.py");
    }

    /**
     * Asks to review one author's snapshot, has the author admit it, consumes the grant, and returns
     * the review decision payload the reviewer is then offered.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> reviewDecision(Producer reviewer, Producer author, String snapshot, String workGroupId) {
        answered(reviewer.handler().handleMessage(toolCall("request_coordination", Map.of("kind", "work_group_join", "payload",
                Map.of("workGroupId", workGroupId, "intentId", author.intentId(), "snapshotId", snapshot, "proposal", "Review " + author.module())))));
        assertEquals("ACCEPTED", ownerAnswer(author).get("coordinationStatus"));
        Map<String, Object> consume = (Map<String, Object>) result(reviewer.handler().handleMessage(toolCall("get_next_action", Map.of()))).get("workflow");
        assertEquals("request_coordination", consume.get("recommendedTool"), consume.toString());
        answered(reviewer.handler().handleMessage(toolCall("request_coordination", (Map<String, Object>) consume.get("arguments"))));
        Map<String, Object> next = result(reviewer.handler().handleMessage(toolCall("get_next_action", Map.of())));
        assertEquals("review_decision", next.get("nextProtocolAction"), next.toString());
        return new LinkedHashMap<>((Map<String, Object>) next.get("nextProtocolPayload"));
    }

    /** Executes the owner's recommended answer to the review admission asked of it and returns that answer. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ownerAnswer(Producer owner) {
        Map<String, Object> next = result(owner.handler().handleMessage(toolCall("get_next_action", Map.of())));
        Map<String, Object> workflow = (Map<String, Object>) next.get("workflow");
        assertEquals("respond_coordination", workflow.get("recommendedTool"), next.toString());
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        answered(owner.handler().handleMessage(toolCall("respond_coordination", arguments)));
        return (Map<String, Object>) arguments.get("payload");
    }

    /**
     * Takes the next step get_next_action offers the producer, preferring the snapshot of
     * {@code preferredAuthor} when it must choose among review candidates.
     *
     * @return {@code true} when a step was taken, {@code false} when the producer was told to wait
     */
    @SuppressWarnings("unchecked")
    private static boolean step(Producer producer, String preferredAuthor) throws Exception {
        Map<String, Object> next = result(producer.handler().handleMessage(toolCall("get_next_action", Map.of())));
        Map<String, Object> workflow = (Map<String, Object>) next.get("workflow");
        Object tool = workflow.get("recommendedTool");
        // ensure_session refreshes a workspace another producer's integration made stale.
        if (tool != null && List.of("request_coordination", "respond_coordination", "ensure_session").contains(tool) && workflow.get("arguments") instanceof Map<?, ?> arguments) {
            return answered(producer.handler().handleMessage(toolCall((String) tool, (Map<String, Object>) arguments)));
        }
        if ("review_decision".equals(next.get("nextProtocolAction"))) {
            Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) next.get("nextProtocolPayload"));
            payload.put("result", "accepted");
            return answered(producer.handler().handleMessage(toolCall("respond_coordination", Map.of("kind", "review_validation", "payload", payload))));
        }
        if ("request_coordination".equals(next.get("nextProtocolAction")) && next.get("actionableReviewCandidates") instanceof List<?> listed && !listed.isEmpty()) {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) listed;
            Map<String, Object> chosen = candidates.stream().filter(candidate -> preferredAuthor.equals(candidate.get("participant"))).findFirst().orElse(candidates.getFirst());
            return answered(producer.handler().handleMessage(toolCall("request_coordination", (Map<String, Object>) chosen.get("requestCoordination"))));
        }
        if (!"COMPLETED".equals(next.get("state"))) {
            assertEquals("wait", next.get("nextAction"), next.toString());
        }
        return false;
    }

    private static boolean answered(String response) {
        assertFalse(response.contains("\"isError\":true"), response);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String publish(Producer producer) throws Exception {
        Files.writeString(producer.worktree().resolve(producer.module()), "def run(value):\n    return value\n");
        Map<String, Object> projection = result(producer.handler().handleMessage(toolCall("get_next_action", Map.of("completionRequested", true))));
        Map<String, Object> workflow = (Map<String, Object>) projection.get("workflow");
        assertEquals("finish_lane", workflow.get("recommendedTool"), projection.toString());
        Map<String, Object> published = result(producer.handler().handleMessage(toolCall("finish_lane", (Map<String, Object>) workflow.get("arguments"))));
        assertEquals("REVIEW_PENDING", published.get("snapshotState"), published.toString());
        return String.valueOf(published.get("snapshotId"));
    }

    private static Producer producer(Path project, String connection, String module) throws Exception {
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", connection, null, false));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var binding = bindings.find(location, "codex", connection).orElseThrow();
        Path worktree = Path.of(binding.worktreePath());
        bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree);
        var claim = new WorkspaceCollaborationService().announce(project, "codex", connection, "Write " + module, "Reviewed by a non-author", List.of(ResourceSelector.pathExact(module)));
        assertTrue(claim.acquired(), module);
        return new Producer(new McpProtocolHandler(sessions, project, "codex", connection), claim.intent().intentId().toString(), WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, module);
    }

    private static Path mandatoryReviewProject(Path project) throws Exception {
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "Mutual Review Test");
        git(project, "config", "user.email", "mutual-review@example.test");
        Files.writeString(project.resolve("README.md"), "# Four modules, each reviewed by a non-author\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(project).location();
        Files.writeString(location.metadataFile(), "{\"schemaVersion\":2,\"projectId\":\"" + location.projectId() + "\",\"createdAt\":\"" + location.createdAt() + "\",\"review\":{\"mandatory\":true}}");
        git(project, "commit", "-am", "require review");
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        return project;
    }

    private static PredictionEventStore store(Path project) throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(project);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(String response) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(response);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        Map<String, Object> envelope = (Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text"));
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) envelope.get("result"));
        result.put("nextAction", envelope.get("nextAction"));
        return result;
    }

    private static String toolCall(String name, Map<String, Object> arguments) {
        return ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)));
    }

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    private record Producer(McpProtocolHandler handler, String intentId, String participant, Path worktree, String module) {

    }
}
