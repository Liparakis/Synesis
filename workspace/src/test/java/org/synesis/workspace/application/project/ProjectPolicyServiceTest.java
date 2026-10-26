package org.synesis.workspace.application.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinAnswered;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinRequested;
import org.synesis.coordination.domain.membership.MembershipEntries.ProjectShared;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.identity.OperatorKeyStore;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectPolicyService.AdoptionChoice;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.test.TestGit;

/**
 * The signed policy through the one door gates read it by (ADR-0104, Part A): the log decides once a baseline exists,
 * the file never does after it, and a log that cannot be read gates nothing and ungates nothing.
 */
final class ProjectPolicyServiceTest {

    private static final ProjectCommandSpec STATUS = new ProjectCommandSpec(List.of("git", "status", "--short"), ".", 30);
    private static final ProjectCommandSpec LOG = new ProjectCommandSpec(List.of("git", "log"), ".", 30);
    private static final ProjectPolicyService.OperatorPrompt TYPES = newKey -> "a passphrase for tests".toCharArray();

    @TempDir
    Path tmp;

    private final ProjectApplicationService service = new ProjectApplicationService();

    private Path gitProject() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        TestGit.run(root, "init");
        TestGit.run(root, "config", "user.name", "Test User");
        TestGit.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Baseline\n");
        TestGit.run(root, "add", "README.md");
        TestGit.run(root, "commit", "-m", "initial commit");
        service.init(root, false);
        return root;
    }

    private static Map<PolicyName, String> validation(ProjectCommandSpec spec) {
        return Map.of(PolicyName.VALIDATION, ProjectMetadataCodec.validationValue(spec));
    }

    private static void commitFile(Path root, String text) throws Exception {
        Files.writeString(root.resolve(".synesis/project.json"), text);
        TestGit.run(root, "add", ".synesis/project.json");
        TestGit.run(root, "commit", "-m", "policy in the file");
    }

    private static String withPolicy(ProjectLocation location, String fields) {
        return "{\"schemaVersion\":2,\"projectId\":\"" + location.projectId() + "\",\"createdAt\":\"" + location.createdAt() + "\"" + fields + "}";
    }

    @Test
    void beforeABaselineTheFileIsThePolicyAsItAlwaysWas() throws Exception {
        Path root = gitProject();
        ProjectLocation file = service.locate(root);
        commitFile(root, withPolicy(file, ",\"validation\":{\"argv\":[\"git\",\"status\"],\"workingDirectory\":\".\",\"timeoutSeconds\":30}"));

        assertEquals(new ProjectCommandSpec(List.of("git", "status"), ".", 30), service.locate(root).validation());
        assertTrue(ProjectPolicyService.read(service.locate(root)).snapshot().isEmpty());
    }

    @Test
    void editingProjectJsonOrCommittingToItChangesNothingOnceTheBaselineExists() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        ProjectPolicyService.decide(start, TYPES, Map.of(PolicyName.VALIDATION, ProjectMetadataCodec.validationValue(STATUS),
                PolicyName.VALIDATION_ALSO_IN_LANE, "true", PolicyName.REVIEW, "MANDATORY"), Optional.empty());
        assertEquals(STATUS, service.locate(root).validation());

        // The gap this ADR closes: the file says no validation, no review, and it is committed too.
        commitFile(root, withPolicy(start, ""));
        Files.writeString(root.resolve(".synesis/project.json"), withPolicy(start, ",\"validation\":{\"argv\":[\"true\"]}"));

        ProjectLocation signed = service.locate(root);
        assertEquals(STATUS, signed.validation(), "editing project.json changes nothing");
        assertTrue(signed.validationAlsoInLane());
        assertTrue(signed.review().mandatory());
        assertEquals(STATUS, service.require(root).validation());
        assertTrue(ProjectPolicyService.fileDisagreement(signed).orElseThrow().contains("the signed policy is used"));
    }

    @Test
    void theLegacyFileWriterIsRefusedOnceTheBaselineExists() throws Exception {
        Path root = gitProject();
        ProjectPolicyService.decide(service.locate(root), TYPES, validation(STATUS), Optional.empty());

        ProjectApplicationException refused = assertThrows(ProjectApplicationException.class, () -> service.setValidation(root, LOG, false));

        assertEquals("POLICY_IS_SIGNED", refused.code());
        assertEquals(STATUS, service.locate(root).validation());
    }

    @Test
    void aLogThatCannotBeReadMakesLocateFailClosedAndNeverFallsBackToTheFile() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        ProjectPolicyService.decide(start, TYPES, validation(STATUS), Optional.empty());
        commitFile(root, withPolicy(start, ",\"validation\":{\"argv\":[\"true\"]}"));
        Path events = start.coordinationDirectory().resolve("events");
        try (var files = Files.list(events)) {
            Path entry = files.filter(Files::isRegularFile).sorted().findFirst().orElseThrow();
            byte[] bytes = Files.readAllBytes(entry);
            bytes[bytes.length / 2] ^= 0x55;
            Files.write(entry, bytes);
        }

        ProjectApplicationException refused = assertThrows(ProjectApplicationException.class, () -> service.locate(root));

        assertEquals("POLICY_UNAVAILABLE", refused.code());
        assertThrows(ProjectApplicationException.class, () -> service.require(root));
    }

    @Test
    void aSecondDecisionIsRefusedWithAWrongPassphraseAndWritesNothing() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        ProjectPolicyService.decide(start, TYPES, validation(STATUS), Optional.empty());

        ProjectApplicationException wrong = assertThrows(ProjectApplicationException.class,
                () -> ProjectPolicyService.decide(service.locate(root), newKey -> "not the passphrase".toCharArray(), validation(LOG), Optional.empty()));

        assertEquals("OPERATOR_PASSPHRASE_WRONG", wrong.code());
        assertEquals(STATUS, service.locate(root).validation());
        ProjectApplicationException nobody = assertThrows(ProjectApplicationException.class,
                () -> ProjectPolicyService.decide(service.locate(root), newKey -> null, validation(LOG), Optional.empty()));
        assertEquals("OPERATOR_KEY_UNAVAILABLE", nobody.code());
    }

    @Test
    void theKeyFileHoldsNoPlaintextAndAKeyThatIsNotTheRegisteredOneIsRefused() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        ProjectPolicyService.decide(start, TYPES, validation(STATUS), Optional.empty());
        assertTrue(new OperatorKeyStore(start.operatorKeyFile()).publicKey().length > 0);
        String file = new String(Files.readAllBytes(start.operatorKeyFile()), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(file.contains("a passphrase for tests"));

        // A different key in the file (another computer's, copied here) is not the registered one.
        Files.delete(start.operatorKeyFile());
        new OperatorKeyStore(start.operatorKeyFile(), 1_000).create("a passphrase for tests".toCharArray());

        ProjectApplicationException mismatch = assertThrows(ProjectApplicationException.class,
                () -> ProjectPolicyService.decide(service.locate(root), TYPES, validation(LOG), Optional.empty()));
        assertEquals("OPERATOR_KEY_MISMATCH", mismatch.code());
        assertEquals(STATUS, service.locate(root).validation());
    }

    @Test
    void repeatingADecisionWritesNothingAndDecidingAgainChangesTheNextRevision() throws Exception {
        Path root = gitProject();
        ProjectPolicyService.Decided first = ProjectPolicyService.decide(service.locate(root), TYPES, validation(STATUS), Optional.empty());
        ProjectPolicyService.Decided again = ProjectPolicyService.decide(service.locate(root), TYPES, validation(STATUS), Optional.empty());
        ProjectPolicyService.Decided changed = ProjectPolicyService.decide(service.locate(root), TYPES, validation(LOG), Optional.empty());

        assertTrue(first.adopted().isPresent(), "the first decision writes the baseline");
        assertEquals(0, again.written());
        assertEquals(first.revision(), again.revision());
        assertEquals(1, changed.written());
        assertEquals(first.revision() + 1, changed.revision());
        assertTrue(changed.adopted().isEmpty());
        assertEquals(LOG, service.locate(root).validation());
    }

    @Test
    void migrationAdoptsTheFileAsCommittedOnTheControlHead() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        commitFile(root, withPolicy(start, ",\"validation\":{\"argv\":[\"git\",\"status\",\"--short\"],\"workingDirectory\":\".\",\"timeoutSeconds\":30,\"alsoInLane\":true},\"review\":{\"mandatory\":true}"));

        ProjectPolicyService.Adoption adoption = ProjectPolicyService.adoption(service.locate(root));
        assertFalse(adoption.needsConfirmation());
        ProjectPolicyService.Decided adopted = ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.empty());

        assertEquals(4, adopted.written(), "the baseline and the three non-default fields");
        ProjectLocation signed = service.locate(root);
        assertEquals(STATUS, signed.validation());
        assertTrue(signed.validationAlsoInLane());
        assertTrue(signed.review().mandatory());

        ProjectPolicyService.Decided repeated = ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.empty());
        assertEquals(0, repeated.written(), "a project with a baseline is not migrated again");
    }

    @Test
    void anEmptyPolicyIsAdoptedAsABaselineAloneAndTheFileHasNothingToConfirm() throws Exception {
        Path root = gitProject();

        ProjectPolicyService.Decided adopted = ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.empty());

        assertEquals(1, adopted.written());
        assertNull(service.locate(root).validation());
        assertTrue(ProjectPolicyService.read(service.locate(root)).snapshot().isPresent());
    }

    @Test
    void aWorkingFileThatDiffersFromTheCommittedOneAdoptsNothingUntilAPersonPicks() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        commitFile(root, withPolicy(start, ",\"validation\":{\"argv\":[\"git\",\"status\",\"--short\"],\"workingDirectory\":\".\",\"timeoutSeconds\":30}"));
        // An edit after the commit, the way an agent or a tool would leave one.
        Files.writeString(root.resolve(".synesis/project.json"), withPolicy(start, ",\"validation\":{\"argv\":[\"true\"],\"workingDirectory\":\".\",\"timeoutSeconds\":30}"));

        assertTrue(ProjectPolicyService.adoption(service.locate(root)).needsConfirmation());
        ProjectApplicationException needs = assertThrows(ProjectApplicationException.class,
                () -> ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.empty()));
        assertEquals("POLICY_ADOPTION_NEEDS_CONFIRMATION", needs.code());
        assertFalse(Files.exists(start.operatorKeyFile()), "nothing was signed and no key was made");
        assertTrue(ProjectPolicyService.read(service.locate(root)).snapshot().isEmpty());

        ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.of(AdoptionChoice.HEAD));

        assertEquals(STATUS, service.locate(root).validation(), "the committed copy was the pick");
    }

    @Test
    void anUncommittedFileWithAPolicyNeedsAPickAndOneWithNoneDoesNot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("plain"));
        ProjectLocation start = service.init(root, false).location();
        assertFalse(ProjectPolicyService.adoption(start).needsConfirmation());
        Files.writeString(start.metadataFile(), withPolicy(start, ",\"review\":{\"mandatory\":true}"));

        ProjectPolicyService.Adoption adoption = ProjectPolicyService.adoption(service.locate(root));

        assertTrue(adoption.head().isEmpty());
        assertTrue(adoption.needsConfirmation());
        ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.of(AdoptionChoice.FILE));
        assertTrue(service.locate(root).review().mandatory());
    }

    @Test
    void theAdoptionRequestIsRaisedOnceWhileTheCopiesDisagreeAndClosedWhenAPersonPicks() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        NodeIdentity node = start.loadOrCreateIdentity();
        commitFile(root, withPolicy(start, ",\"review\":{\"mandatory\":true}"));
        Files.writeString(root.resolve(".synesis/project.json"), withPolicy(start, ""));
        PredictionEventStore store = start.openCoordinationStore();

        assertTrue(ProjectPolicyService.reconcileAdoptionRequest(service.locate(root), store, node));
        assertTrue(ProjectPolicyService.reconcileAdoptionRequest(service.locate(root), store, node), "still open, not raised again");
        assertEquals(1, store.operatorRequestProjection().open().stream().filter(request -> request.condition().equals(ProjectPolicyService.ADOPTION_CONDITION)).count());

        ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(), Optional.of(AdoptionChoice.HEAD));
        store.catchUp();

        assertFalse(ProjectPolicyService.reconcileAdoptionRequest(service.locate(root), store, node));
        assertTrue(store.operatorRequestProjection().open(ProjectPolicyService.ADOPTION_CONDITION).isEmpty());
    }

    @Test
    void aMemberThatHasNotReceivedTheOwnersBaselineRefusesEveryGateAndOnlyThat() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        NodeIdentity self = start.loadOrCreateIdentity();
        NodeIdentity owner = NodeIdentity.generate();
        // The owner's log, shared and admitting this computer, arrives as a copy; it holds no baseline.
        Path ownerDirectory = tmp.resolve("owner");
        PredictionEventStore ownerStore = new PredictionEventStore(ownerDirectory, start.projectId());
        ownerStore.append(UUID.randomUUID(), PredictionEventType.PROJECT_SHARED, owner.nodeId(), new ProjectShared(owner.publicKeyEncoded(), new byte[0]).encode(), owner);
        UUID request = UUID.randomUUID();
        ownerStore.append(UUID.randomUUID(), PredictionEventType.JOIN_REQUESTED, owner.nodeId(), new JoinRequested(request, self.nodeId()).encode(), owner);
        ownerStore.append(UUID.randomUUID(), PredictionEventType.JOIN_ANSWERED, owner.nodeId(), new JoinAnswered(request, self.nodeId(), true, 0).encode(), owner);
        PredictionEventStore memberStore = start.openCoordinationStore();
        memberStore.placeCopies(owner.nodeId(), ownerStore.entriesAfter(PredictionEventStore.OWN_LOG, 0, 100, Integer.MAX_VALUE));

        ProjectLocation member = service.locate(root);

        assertFalse(member.policyReceived());
        assertEquals(root.toAbsolutePath().normalize(), member.root(), "everything but a gate still works");
        assertThrows(PolicyNotReceivedException.class, member::validation);
        assertThrows(PolicyNotReceivedException.class, member::review);
        assertThrows(PolicyNotReceivedException.class, member::validationAlsoInLane);
        ProjectApplicationException notOwner = assertThrows(ProjectApplicationException.class, () -> ProjectPolicyService.decide(member, TYPES, validation(STATUS), Optional.empty()));
        assertEquals("NOT_THE_OWNER", notOwner.code());
    }

    @Test
    void anOwnersSharedProjectKeepsItsPolicyAfterSharingFromTheBaselineComputer() throws Exception {
        Path root = gitProject();
        ProjectLocation start = service.locate(root);
        ProjectPolicyService.decide(start, TYPES, validation(STATUS), Optional.empty());
        new MembershipService(service.locate(root).openCoordinationStore(), start.loadOrCreateIdentity()).share(Optional.empty());

        ProjectLocation owner = service.locate(root);

        assertTrue(owner.policyReceived());
        assertEquals(STATUS, owner.validation());
        ProjectPolicyService.Decided later = ProjectPolicyService.decide(owner, TYPES, Map.of(PolicyName.REVIEW, "MANDATORY"), Optional.empty());
        assertEquals(1, later.written());
        assertTrue(service.locate(root).review().mandatory());
    }

    @Test
    void noPolicyReadsTheFileOutsideTheCodecAndTheMigration() throws IOException {
        Path source = Path.of("src/main/java/org/synesis/workspace");
        Path module = Files.isDirectory(source) ? source : Path.of("workspace").resolve(source);
        try (var files = Files.walk(module)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("ProjectMetadataCodec.java") || name.equals("ProjectPolicyService.java") || name.equals("ProjectApplicationService.java")) {
                    continue;
                }
                assertFalse(Files.readString(file).contains("ProjectMetadataCodec.read"), name + " reads policy from the file; only the codec, the migration and the location door may");
            }
        }
    }
}
