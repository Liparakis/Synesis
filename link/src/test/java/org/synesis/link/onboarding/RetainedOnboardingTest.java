package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

/**
 * Sessions a runtime keeps outlive one exchange: they run under tolerant timing, and the computer that dialed the
 * invitation can dial the host again after the session ended, with no new invitation, for as long as the host's rule
 * names it (ADR-0098).
 */
@Timeout(120)
final class RetainedOnboardingTest {

  private final List<AutoCloseable> toClose = new CopyOnWriteArrayList<>();
  private final List<PeerSession> hostSessions = new CopyOnWriteArrayList<>();
  private final List<PeerSession> joinSessions = new CopyOnWriteArrayList<>();
  private final AtomicBoolean joinerMayReturn = new AtomicBoolean(true);

  @AfterEach
  void close() {
    for (AutoCloseable resource : toClose.reversed()) {
      try {
        resource.close();
      } catch (Exception ignored) {
        // Best effort.
      }
    }
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    assertTrue(condition.getAsBoolean(), "condition not reached");
  }

  /** A retained host and a retained joiner, connected; the host's rule follows {@link #joinerMayReturn}. */
  private Onboarding.PreparedJoin connected() throws Exception {
    List<OnboardingEvent> events = new CopyOnWriteArrayList<>();
    Onboarding host = new Onboarding(Files.createTempDirectory("synesis-retained-host"), events::add);
    Onboarding.PreparedHost invitation = host.createRetainedInvitation(null, false, null,
        hostSessions::add, node -> joinerMayReturn.get());
    toClose.add(invitation);
    CompletableFuture.runAsync(() -> {
      try {
        invitation.awaitConnectionAndKeepAlive();
      } catch (OnboardingFailure ignored) {
        // Observed through the sessions.
      }
    });
    // The host's wait delivers the first session to the action after the descriptors were exchanged.
    Onboarding.PreparedJoin join = new Onboarding(Files.createTempDirectory("synesis-retained-join"),
        ignored -> {
        }).importRetainedInvitation(invitation.invitationLink(), null, joinSessions::add);
    toClose.add(join);
    join.connectAndKeepAlive();
    await(() -> hostSessions.size() == 1);
    return join;
  }

  @Test
  void retainedSessionsTolerateStallsThatOneShotSessionsDoNot() throws Exception {
    connected();

    assertEquals(LivenessConfiguration.RETAINED, joinSessions.getFirst().livenessConfiguration());
    assertEquals(LivenessConfiguration.RETAINED, hostSessions.getFirst().livenessConfiguration());
    assertTrue(LivenessConfiguration.RETAINED.expiryTimeout().compareTo(Duration.ofSeconds(30)) >= 0);
    assertTrue(LivenessConfiguration.RETAINED.heartbeatInterval().compareTo(Duration.ofSeconds(15)) <= 0);
    assertEquals(Duration.ofSeconds(60), OnboardingEndpoint.RETAINED_QUIC_IDLE);
  }

  @Test
  void theOneShotPathKeepsTheDefaultTiming() throws Exception {
    Onboarding host = new Onboarding(Files.createTempDirectory("synesis-oneshot-host"), ignored -> {
    });
    List<PeerSession> seen = new CopyOnWriteArrayList<>();
    Onboarding.PreparedHost invitation = host.createCompactInvitation(null, null, seen::add);
    toClose.add(invitation);
    CompletableFuture<PeerSession> hosted = CompletableFuture.supplyAsync(() -> {
      try {
        return invitation.awaitConnectionAndKeepAlive();
      } catch (OnboardingFailure failure) {
        throw new IllegalStateException(failure);
      }
    });
    Onboarding.PreparedJoin join = new Onboarding(Files.createTempDirectory("synesis-oneshot-join"),
        ignored -> {
        }).importInvitation(invitation.invitationLink(), null, ignored -> {
        });
    toClose.add(join);

    PeerSession joined = join.connectAndKeepAlive();

    assertEquals(LivenessConfiguration.DEFAULT, joined.livenessConfiguration());
    assertEquals(LivenessConfiguration.DEFAULT, hosted.get(30, TimeUnit.SECONDS).livenessConfiguration());
    assertThrows(OnboardingFailure.class, join::redial);
  }

  @Test
  void theJoinerDialsTheHostAgainAfterTheSessionEndedWithoutANewInvitation() throws Exception {
    Onboarding.PreparedJoin join = connected();
    PeerSession before = joinSessions.getFirst();
    before.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture().get(10, TimeUnit.SECONDS);
    await(() -> hostSessions.getFirst().closeReason() != null);

    PeerSession again = join.redial();

    assertNotSame(before, again);
    assertEquals(before.remoteNodeId(), again.remoteNodeId());
    assertTrue(again.isUsable());
    await(() -> hostSessions.size() == 2);
    assertEquals(again.sessionId(), hostSessions.get(1).sessionId());
    assertEquals(joinSessions.size(), 2);
  }

  @Test
  void aHostWhoseRuleNoLongerNamesTheJoinerRefusesTheRedial() throws Exception {
    Onboarding.PreparedJoin join = connected();
    joinSessions.getFirst().closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
    joinerMayReturn.set(false);

    assertThrows(OnboardingFailure.class, join::redial);

    assertEquals(1, hostSessions.size());
    assertFalse(joinSessions.size() > 1);
  }
}
