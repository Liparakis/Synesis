package org.synesis.workspace.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleInventoryService;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupEligibilityService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * The environment, network and work groups of {@code synesis doctor} (ADR-0103, decision 4): what each finds, how it is
 * marked, that each carries a fix, and how the report lays the groups out.
 */
class DoctorGroupsTest {

  @TempDir Path temp;

  private String previousUserHome;

  @BeforeEach
  void isolateUserHome() throws Exception {
    previousUserHome = System.getProperty("user.home");
    System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
  }

  @AfterEach
  void restoreUserHome() {
    if (previousUserHome == null) {
      System.clearProperty("user.home");
    } else {
      System.setProperty("user.home", previousUserHome);
    }
  }

  private static final class Probe implements NetworkProbe {
    Optional<Boolean> running = Optional.empty();
    Optional<Firewall> firewall = Optional.empty();

    @Override
    public Optional<Boolean> runtimeRunning(Path projectRoot) {
      return running;
    }

    @Override
    public Optional<Firewall> firewall() {
      return firewall;
    }
  }

  private DoctorService doctor(BooleanSupplier git, Predicate<String> onPath, NetworkProbe network) {
    return new DoctorService(
        new ProjectApplicationService(),
        new LifecycleInventoryService(),
        new CleanupEligibilityService(),
        new SessionLeaseService(),
        new SessionLeaseStore(),
        ProcessInspector.system(),
        () -> temp.resolve("no-installer/Synesis/bin/synesis"),
        git,
        onPath,
        network);
  }

  private Path project(String name) throws Exception {
    Path root = Files.createDirectories(temp.resolve(name));
    new ProjectApplicationService().init(root);
    return root;
  }

  private static List<DoctorFinding> in(DoctorReport report, DoctorGroup group) {
    return report.findings().stream().filter(finding -> finding.group() == group).toList();
  }

  private static void assertHasFix(DoctorFinding finding) {
    assertFalse(finding.fix().isBlank(), finding.code() + " has a fix");
  }

  @Test
  void missingGitIsAConfirmedEnvironmentErrorWithAFix() throws Exception {
    DoctorReport report = doctor(() -> false, name -> true, NetworkProbe.UNKNOWN).diagnose(project("p"));

    DoctorFinding git = in(report, DoctorGroup.ENVIRONMENT).getFirst();
    assertEquals(DoctorFindingCode.GIT_MISSING, git.code());
    assertEquals("confirmed", git.confidence().mark());
    assertEquals(DoctorSeverity.ERROR, git.severity());
    assertTrue(git.fix().contains("git-scm.com"), git.fix());
    assertEquals(DoctorStatus.UNHEALTHY, report.overallStatus());
  }

  @Test
  void aConnectedAgentThatIsNotOnThePathIsSuspectedAndOneWhoseFilesAreBrokenIsConfirmed() throws Exception {
    Path root = project("connected");
    Path metadata = root.resolve(".synesis/local/providers/codex.json");
    Files.createDirectories(metadata.getParent());
    Files.writeString(metadata, "{}");

    DoctorReport missing = doctor(() -> true, name -> false, NetworkProbe.UNKNOWN).diagnose(root);
    DoctorReport present = doctor(() -> true, name -> true, NetworkProbe.UNKNOWN).diagnose(root);

    DoctorFinding notOnPath = in(missing, DoctorGroup.ENVIRONMENT).stream().filter(f -> f.code() == DoctorFindingCode.AGENT_NOT_ON_PATH).findFirst().orElseThrow();
    assertEquals("suspected", notOnPath.confidence().mark());
    assertEquals(Map.of("provider", "codex"), notOnPath.details());
    assertTrue(notOnPath.fix().contains("codex"), notOnPath.fix());
    DoctorFinding stale = in(missing, DoctorGroup.ENVIRONMENT).stream().filter(f -> f.code() == DoctorFindingCode.PROVIDER_FILES_STALE).findFirst().orElseThrow();
    assertEquals("confirmed", stale.confidence().mark());
    assertTrue(stale.fix().contains("synesis doctor --fix"), stale.fix());
    List<DoctorFindingCode> found = in(present, DoctorGroup.ENVIRONMENT).stream().map(DoctorFinding::code).toList();
    assertTrue(found.contains(DoctorFindingCode.PROVIDER_FILES_STALE), found.toString());
    assertFalse(found.contains(DoctorFindingCode.AGENT_NOT_ON_PATH), found.toString());
    in(missing, DoctorGroup.ENVIRONMENT).forEach(DoctorGroupsTest::assertHasFix);
  }

  @Test
  void aProjectWithNoConnectedAgentHasNoAgentFindingsWhateverThePath() throws Exception {
    DoctorReport report = doctor(() -> true, name -> false, NetworkProbe.UNKNOWN).diagnose(project("alone"));

    assertEquals(List.of(), in(report, DoctorGroup.ENVIRONMENT));
  }

  @Test
  void aSharedProjectWithoutARuntimeOrFirewallRuleIsReportedInTheNetworkGroup() throws Exception {
    Path root = project("shared");
    ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(root.resolve(".synesis/coordination")));
    Probe probe = new Probe();
    probe.running = Optional.of(false);
    probe.firewall = Optional.of(NetworkProbe.Firewall.STALE);

    DoctorReport report = doctor(() -> true, name -> true, probe).diagnose(root);

    assertTrue(report.shared());
    assertEquals(List.of(DoctorFindingCode.PROJECT_RUNTIME_NOT_RUNNING, DoctorFindingCode.LINK_FIREWALL_STALE), in(report, DoctorGroup.NETWORK).stream().map(DoctorFinding::code).toList());
    assertEquals("suspected", in(report, DoctorGroup.NETWORK).getFirst().confidence().mark());
    assertEquals("confirmed", in(report, DoctorGroup.NETWORK).get(1).confidence().mark());
    in(report, DoctorGroup.NETWORK).forEach(DoctorGroupsTest::assertHasFix);
    probe.firewall = Optional.of(NetworkProbe.Firewall.MISSING);
    assertEquals(DoctorFindingCode.LINK_FIREWALL_MISSING, in(doctor(() -> true, name -> true, probe).diagnose(root), DoctorGroup.NETWORK).get(1).code());
  }

  @Test
  void aProjectNotSharedHasNoNetworkFindingsAndAProbeThatCannotTellAddsNone() throws Exception {
    Probe probe = new Probe();
    probe.running = Optional.of(false);
    probe.firewall = Optional.of(NetworkProbe.Firewall.MISSING);
    Path alone = project("alone");
    Path shared = project("shared");
    ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(shared.resolve(".synesis/coordination")));

    DoctorReport notShared = doctor(() -> true, name -> true, probe).diagnose(alone);
    DoctorReport unknown = doctor(() -> true, name -> true, NetworkProbe.UNKNOWN).diagnose(shared);

    assertFalse(notShared.shared());
    assertEquals(List.of(), in(notShared, DoctorGroup.NETWORK));
    assertEquals(List.of(), in(unknown, DoctorGroup.NETWORK));
  }

  @Test
  void anOpenOperatorRequestIsAdviceThatDoesNotChangeTheHealth() throws Exception {
    Path root = project("operator");
    ProjectLocation location = new ProjectApplicationService().locate(root);
    OperatorRequest asked = OperatorRequest.askedByLane("opr_lane000000000001", UUID.randomUUID().toString(), "Which license?", "participant agt_x");
    var identity = location.loadOrCreateIdentity();
    location.openCoordinationStore().append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), asked.encodeRaised(), identity);

    DoctorReport report = doctor(() -> true, name -> true, NetworkProbe.UNKNOWN).diagnose(root);

    DoctorFinding advice = in(report, DoctorGroup.WORK_GROUPS).getFirst();
    assertEquals(DoctorFindingCode.OPERATOR_REQUESTS_OPEN, advice.code());
    assertEquals("advice", advice.confidence().mark());
    assertEquals(Map.of("open", "1"), advice.details());
    assertEquals("run 'synesis operator list'", advice.fix());
    assertEquals(DoctorStatus.HEALTHY, report.overallStatus());
  }

  @Test
  void findingsFromBeforeTheGroupsAreGroupedAndEveryOneHasAFix() throws Exception {
    Path root = project("old");
    Path admin = Files.createDirectories(org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier.resolveWorkspaceRoot(root).resolve("admin"));
    Files.writeString(admin.resolve("cleanup-execution.lock"), "{ \"pid\": 9999999 }");

    DoctorReport report = doctor(() -> true, name -> true, NetworkProbe.UNKNOWN).diagnose(root);

    DoctorFinding lock = report.findings().stream().filter(f -> f.code() == DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK).findFirst().orElseThrow();
    assertEquals(DoctorGroup.PROJECT, lock.group());
    assertEquals("suspected", lock.confidence().mark(), "strong but not exact evidence is suspected");
    report.findings().forEach(DoctorGroupsTest::assertHasFix);
  }

  @Test
  void theConciseReportKeepsEveryEarlierLineAndAddsOneSectionPerGroup() throws Exception {
    Path root = project("render");
    ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(root.resolve(".synesis/coordination")));
    Probe probe = new Probe();
    probe.running = Optional.of(false);

    DoctorReport report = doctor(() -> false, name -> true, probe).diagnose(root);
    String concise = DoctorRenderer.renderConcise(report);
    List<String> lines = concise.lines().toList();

    assertEquals("DOCTOR_RESULT=UNHEALTHY", lines.getFirst());
    assertTrue(lines.contains("MUTATIONS_PERFORMED=0"));
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("NEXT_ACTION=")));
    assertTrue(lines.indexOf("ENVIRONMENT_STATUS=ATTENTION") > lines.indexOf(lines.stream().filter(line -> line.startsWith("NEXT_ACTION=")).findFirst().orElseThrow()), "the groups come after the earlier lines");
    assertTrue(lines.contains("ENVIRONMENT_FINDINGS=1"));
    assertTrue(lines.contains("ENVIRONMENT_FINDING_1=git_missing"));
    assertTrue(lines.contains("ENVIRONMENT_FINDING_1_MARK=confirmed"));
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("ENVIRONMENT_FINDING_1_FIX=install Git")));
    assertTrue(lines.contains("NETWORK_STATUS=ATTENTION"));
    assertTrue(lines.contains("NETWORK_FINDING_1=project_runtime_not_running"));
    assertTrue(lines.contains("NETWORK_FINDING_1_MARK=suspected"));
    assertTrue(lines.contains("WORK_GROUPS_STATUS=OK"));
    assertTrue(lines.contains("WORK_GROUPS_FINDINGS=0"));
  }

  @Test
  void theNetworkGroupSaysItDoesNotApplyToAProjectThatIsNotShared() throws Exception {
    DoctorReport report = doctor(() -> true, name -> true, NetworkProbe.UNKNOWN).diagnose(project("solo"));

    List<String> lines = DoctorRenderer.renderConcise(report).lines().toList();

    assertTrue(lines.contains("NETWORK_STATUS=NOT_APPLICABLE"), lines.toString());
    assertTrue(lines.contains("ENVIRONMENT_STATUS=OK"), lines.toString());
  }

  @Test
  void theMutationCountIsWhatDoctorFixReports() throws Exception {
    DoctorReport report = doctor(() -> true, name -> true, NetworkProbe.UNKNOWN).diagnose(project("fixed"));

    assertTrue(DoctorRenderer.renderConcise(report, 2).lines().anyMatch("MUTATIONS_PERFORMED=2"::equals));
    assertTrue(DoctorRenderer.renderJson(report, 2).contains("\"mutationsPerformed\":2"));
  }

  @Test
  void theJsonReportCarriesEachFindingsGroupMarkAndFixAndTheGroupStatuses() throws Exception {
    DoctorReport report = doctor(() -> false, name -> true, NetworkProbe.UNKNOWN).diagnose(project("json"));

    String json = DoctorRenderer.renderJson(report);

    assertTrue(json.contains("\"group\":\"environment\""), json);
    assertTrue(json.contains("\"mark\":\"confirmed\""), json);
    assertTrue(json.contains("\"fix\":\"install Git"), json);
    assertTrue(json.contains("\"groups\":{\"environment\":\"ATTENTION\",\"network\":\"NOT_APPLICABLE\",\"work_groups\":\"OK\"}"), json);
  }

  @Test
  void aMarkIsConfirmedSuspectedOrAdviceAndNothingElse() {
    assertEquals("confirmed", DoctorConfidence.CONFIRMED.mark());
    assertEquals("suspected", DoctorConfidence.HIGH_CONFIDENCE.mark());
    assertEquals("suspected", DoctorConfidence.SUSPECTED.mark());
    assertEquals("suspected", DoctorConfidence.AMBIGUOUS.mark());
    assertEquals("advice", DoctorConfidence.ADVICE.mark());
  }

  @Test
  void doctorWritesNothingWhileLookingAtTheGroups() throws Exception {
    Path root = project("readonly");
    Path metadata = root.resolve(".synesis/local/providers/codex.json");
    Files.createDirectories(metadata.getParent());
    Files.writeString(metadata, "{}");
    long before = count(root);

    doctor(() -> true, name -> false, NetworkProbe.UNKNOWN).diagnose(root);

    assertEquals(before, count(root));
  }

  private static long count(Path dir) throws IOException {
    try (var stream = Files.walk(dir)) {
      return stream.count();
    }
  }
}
