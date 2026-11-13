package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.application.provider.ProviderRegistry;

/**
 * Verifies when the controller resumes an agent, and that it stops instead of resuming forever.
 *
 * <p>Time is the controller's own tick: each pause advances a scripted project state by one step,
 * and each fake process lives for a fixed number of ticks.</p>
 */
final class AgentRunControllerTest {

    private static final Path PROJECT = Path.of("project").toAbsolutePath();

    @Test
    void agentIsResumedWhenAnotherLaneProgressesAfterItStoppedAndTheRunEndsWhenWorkCompletes() throws Exception {
        Script script = new Script();
        // claude stops at tick 2. codex makes progress at tick 4, which wakes claude, and its last
        // act at tick 6 completes the work while the resumed claude is still running.
        script.lifetimes(2, 6, 3);
        script.at(0).head(5).announced();
        script.at(4).head(9).progressAfter(5);
        script.at(6).head(14).progressAfter(9).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertEquals(1, result.resumes());
        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertTrue(script.events.contains("AGENT_RESUMED=claude#1 resume=1"), script.events.toString());
        assertEquals(AgentRunController.RESUME_PROMPT, script.prompts.getLast(), "a resumed agent is told only to continue");
    }

    @Test
    void everyProcessGetsItsOwnConnectionAndItsEndIsReportedBeforeTheAgentIsResumed() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 6, 3);
        script.at(0).head(5).announced();
        script.at(4).head(9).progressAfter(5);
        script.at(6).head(14).progressAfter(9).complete();

        script.run(8);

        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-1", "open conn-3", "ended conn-2", "ended conn-3"), script.connections,
                "a resumed process is a new connection, opened only after the previous one was reported ended");
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-1"), script.environments.get(0), "Claude Code passes its environment to the MCP server");
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-2"), script.environments.get(1), "Codex's hooks read it from its environment");
        assertTrue(script.commands.get(1).contains("mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID=conn-2"), script.commands.get(1).toString());
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-3"), script.environments.get(2));
    }

    /**
     * Overnight run across two computers: an agent that ended its turn on WAIT came back as a new
     * session, its lane held for recovery, and claimed a new module instead. A kept session is resumed
     * on its own connection, so into its own lane, and is ended only when the run ends.
     */
    @Test
    void aStoppedAgentWithAnOpenLaneKeepsItsSessionAndIsResumedOnTheSameConnection() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.lifetimes(2, 6, 3);
        script.at(0).head(5).announced();
        script.at(4).head(9).progressAfter(5);
        script.at(6).head(14).progressAfter(9).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-1"), script.environments.get(2), "the resume is the same session");
        assertEquals(1, script.events.stream().filter("AGENT_SESSION_KEPT=claude#1"::equals).count(), script.events.toString());
        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-1", "ended conn-2"), script.connections,
                "no connection is ended while its agent may come back; both are ended when the run does");
    }

    @Test
    void aKeptSessionIsEndedOnceItsLaneEndsAndItsAgentIsResumedOnceInANewSession() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        // claude's lane is open when it stops and ends afterwards; codex keeps the run going.
        script.lifetimes(1, 20, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).laneEnded("claude#1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-1", "open conn-3"), script.connections.subList(0, 4),
                "its session is ended when its lane ends, and the next process is a new session");
        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches,
                "resumed once to take the next work, then not again: it holds no lane");
        assertEquals(List.of(3), script.resumedAtTicks);
    }

    /**
     * A kept session whose participant was suspended (its keeper stopped renewing past the grace period)
     * was resumed on the same connection, refused SESSION_EPOCH_FENCED at every call, and idled until
     * the run ended. It is ended, and its agent is resumed once on a new connection, where continuing
     * its held lane is offered to it, without waiting for progress.
     */
    @Test
    void anAgentWhoseKeptSessionWasFencedIsResumedOnceInANewSessionToContinueItsLane() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.lifetimes(1, 20, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced();
        script.at(3).head(7).fenced("claude#1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches, "resumed once, though nothing progressed");
        assertEquals(List.of(3), script.resumedAtTicks);
        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-1", "open conn-3"), script.connections.subList(0, 4), "the fenced session is ended, the resume is a new one");
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-3"), script.environments.get(2));
    }

    /**
     * An end the project lock refused was given up, and the lease went on naming the controller for
     * an agent that had ended, its lane looking held by a live session until the run ended. It is
     * offered again at each observation until it is recorded.
     */
    @Test
    void anEndTheProjectCouldNotRecordIsOfferedAgainUntilItIsRecorded() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.busyEnds = 2;
        script.lifetimes(1, 20, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).laneEnded("claude#1");

        script.run(8, Duration.ofSeconds(10));

        List<String> conn1 = script.connections.stream().filter(line -> line.endsWith(" conn-1")).toList();
        assertEquals(List.of("open conn-1", "busy conn-1", "busy conn-1", "ended conn-1"), conn1, "the end is offered at each look until the project records it");
        assertTrue(script.connections.indexOf("ended conn-1") < script.connections.indexOf("ended conn-2"), "while the run goes on: " + script.connections);
    }

    @Test
    void runWhereEveryLaneWasCancelledIsNotReportedAsCompleted() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 1);
        script.at(0).head(3).announced();
        script.at(1).head(6).allCancelled();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.ALL_WORK_CANCELLED, result.outcome(), "nothing was built, so the run did not complete");
    }

    @Test
    void stoppedOwnerIsResumedOnceWhenItsAnswerPeriodEndsEvenThoughNothingWasRecorded() throws Exception {
        Script script = new Script();
        // Both agents stop at tick 1 and the log never moves. claude owns a proposal whose answer
        // period ends at tick 5; resumed then, it finishes and the work completes.
        script.lifetimes(1, 1, 1);
        script.at(0).head(3).announced();
        script.answerDeadlines.put("claude", 5_000L);
        script.at(6).head(8).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches, "only the provider that owns the proposal is woken, and only once");
        assertEquals(5, script.resumedAtTicks.getFirst(), "the run must wait for the deadline instead of ending while every agent is stopped");
        assertEquals(1, result.resumes());
        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
    }

    @Test
    void ownerIsNotResumedAgainForTheSameDeadline() throws Exception {
        Script script = new Script();
        // The resumed owner stops again without finishing and the deadline stays as it was.
        script.lifetimes(1, 1, 1);
        script.at(0).head(3).announced();
        script.answerDeadlines.put("claude", 3_000L);

        AgentRunController.Result result = script.run(8);

        assertEquals(1, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
    }

    @Test
    void nothingIsResumedWhenNoWorkProgressedAfterEveryAgentStopped() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
        assertEquals(0, result.resumes());
        assertEquals(2, script.launches.size(), "an idle project must not be poked");
    }

    @Test
    void aRunWhoseAgentsAllStoppedKeepsPollingWhileAnotherActiveParticipantHoldsALaneAndIsTimedOutAtTheDeadline() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced().foreignLane();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(30));

        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertEquals(0, result.resumes());
        assertEquals(30, script.tick, "it waited for the deadline instead of stalling");
    }

    @Test
    void anAgentStoppedWhileAForeignLaneIsOpenIsResumedWhenThatLaneProgressesAndTheRunCompletes() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3, 3);
        script.at(0).head(5).announced().foreignLane();
        script.at(10).head(9).progressAfter(5).foreignLane();
        script.at(14).head(12).progressAfter(9).complete();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(60));

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertTrue(result.resumes() >= 1, "the review another computer gave woke an agent here");
        assertTrue(script.launches.stream().anyMatch(launch -> launch.startsWith("resume ")), script.launches.toString());
    }

    @Test
    void withNoForeignActiveLaneAStoppedRunStallsAsBefore() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced();

        assertEquals(AgentRunController.Outcome.STALLED, script.run(8, Duration.ofSeconds(30)).outcome());
        assertTrue(script.tick < 30);
    }

    /**
     * The first run across two homes (2026-10-17): both agents here published their work, waited
     * for a review nobody here could give, and stopped before the other computer's agents had
     * claimed anything. Nothing foreign held a lane yet, and the run ended STALLED with no resume.
     */
    @Test
    void agentsStoppedWithTheirLanesOpenWhileAnotherComputerIsConnectedAreResumedWhenItsReviewArrives() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3, 3, 3);
        script.at(0).head(7).announced().otherComputer();
        script.at(40).head(9).progressAfter(7).otherComputer();
        script.at(46).head(12).progressAfter(9).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertEquals(2, result.resumes(), "the review from the other computer woke both authors");
        assertEquals(List.of(40, 40), script.resumedAtTicks);
        assertEquals(List.of("AGENT_STARTED=claude#1", "AGENT_STARTED=codex#2", "AGENT_EXITED=claude#1 exitCode=0", "AGENT_EXITED=codex#2 exitCode=0",
                "AGENT_RUN_WAITING=OTHER_COMPUTER_CONNECTED", "AGENT_RESUMED=claude#1 resume=1", "AGENT_RESUMED=codex#2 resume=1",
                "AGENT_EXITED=claude#1 exitCode=0", "AGENT_EXITED=codex#2 exitCode=0", "AGENT_RUN_WAITING=OTHER_COMPUTER_CONNECTED"), script.events,
                "each wait is reported once, when it begins");
    }

    /**
     * The PC and VM run s1-run4 (ADR-0098 step 4): a reviewer on the PC held the grant for a snapshot whose code is on
     * the VM, and the VM's cable was cut. Every read failed {@code CODE_REMOTE_UNAVAILABLE}; it recorded no verdict and
     * stopped. The VM came back twenty minutes later with nothing new to send, so the PC's log never moved again, the
     * controller printed {@code AGENT_RUN_WAITING=FOREIGN_LANE_OPEN} and resumed nobody, and the review waited for ever.
     */
    @Test
    void aStoppedAgentWithAnOpenLaneIsResumedOnceForEachTimeAnotherComputerComesBackAlthoughTheLogDidNotMove() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(3, 2, 2);
        script.at(0).head(41).announced().foreignLane().otherComputer();
        script.at(10).head(41).foreignLane();
        script.at(40).head(41).foreignLane().cameBackAt(40);
        script.at(70).head(41).foreignLane().cameBackAt(70);

        script.run(8, Duration.ofSeconds(90));

        assertEquals(List.of(40, 70), script.resumedAtTicks, "once when the VM came back, and once more after the next cut");
    }

    @Test
    void aComputerThatCameBackBeforeTheAgentLastLookedResumesNobody() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(45);
        script.at(0).head(41).announced().foreignLane();
        script.at(5).head(41).foreignLane().cameBackAt(5);

        script.run(8, Duration.ofSeconds(80));

        assertEquals(List.of(), script.resumedAtTicks, "it was running for half a minute after the computer came back");
    }

    @Test
    void aComputerComingBackResumesAnAgentHoldingAGrantButNotOneWithNothingToComeBackTo() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.lifetimes(2, 2, 2);
        script.withoutOwnLane.addAll(List.of("claude#1", "codex#2"));
        script.at(0).head(41).announced().foreignLane().holdsRequest("claude#1");
        script.at(40).head(41).foreignLane().holdsRequest("claude#1").cameBackAt(40);

        script.run(8, Duration.ofSeconds(60));

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches, "only the agent that holds the review grant");
    }

    /**
     * s5-run14 (PC + VM, 2026-10-19): the PC's reviewer held the VM's gcd grant, was refused the code at the heal, and
     * stopped. The journal recorded no reconnection, the log had nothing more to bring, and it was never resumed, so the
     * review waited for ever while both runs printed {@code FOREIGN_LANE_OPEN}.
     */
    @Test
    void aReviewerOfAnotherComputersSnapshotIsAskedAgainWithGrowingPausesWhileThatComputerIsConnected() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude");
        script.lifetimes(2, 1, 1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(41).announced().foreignLane().otherComputer().holdsRequest("claude#1").foreignReview("claude#1");

        script.run(8, Duration.ofSeconds(400));

        assertEquals(List.of(32, 93, 214), script.resumedAtTicks.subList(0, 3), "30 s, then 60 s, then 120 s after each stop");
    }

    @Test
    void aReviewerIsNotAskedAgainOnceItsReviewIsGivenOrWhileTheOtherComputerIsAway() throws Exception {
        Script given = new Script();
        given.keepSessions = true;
        given.providers = List.of("claude");
        given.lifetimes(2, 1);
        given.withoutOwnLane.add("claude#1");
        given.at(0).head(41).announced().foreignLane().otherComputer().holdsRequest("claude#1").foreignReview("claude#1");
        given.at(20).head(42).otherComputer().foreignLane().holdsRequest("claude#1");
        given.run(8, Duration.ofSeconds(200));
        assertEquals(List.of(), given.resumedAtTicks, "it gave its verdict, so it holds nothing to come back to");

        Script away = new Script();
        away.keepSessions = true;
        away.providers = List.of("claude");
        away.lifetimes(2, 1);
        away.withoutOwnLane.add("claude#1");
        away.at(0).head(41).announced().foreignLane().otherComputerAway().holdsRequest("claude#1").foreignReview("claude#1");
        away.run(8, Duration.ofSeconds(200));
        assertEquals(List.of(), away.resumedAtTicks, "no ask while the journal says the computer is away");
    }

    @Test
    void aGrantOnASnapshotOfThisComputerIsNotRetriedOnATimer() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude");
        script.lifetimes(2, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(41).announced().foreignLane().otherComputer().holdsRequest("claude#1");

        script.run(8, Duration.ofSeconds(200));

        assertEquals(List.of(), script.resumedAtTicks, "only a review that needs another computer's code is asked again");
    }

    @Test
    void theAsksThatRecordNothingAreBoundedByTheResumeLimit() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude");
        script.lifetimes(2, 1, 1, 1, 1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(41).announced().foreignLane().otherComputer().holdsRequest("claude#1").foreignReview("claude#1");

        script.run(2, Duration.ofSeconds(4000));

        assertEquals(2, script.resumedAtTicks.size(), "two idle resumes, then its session is left to end and its review released");
    }

    @Test
    void aRunWaitingForAnotherComputerThatNeverActsIsTimedOutAtTheDeadline() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced().otherComputer();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(30));

        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertEquals(0, result.resumes());
        assertEquals(30, script.tick, "it waited for the deadline instead of stalling");
        assertTrue(script.events.contains("AGENT_RUN_WAITING=OTHER_COMPUTER_CONNECTED"), script.events.toString());
    }

    /** ADR-0102 item 6: a peer that is known but away no longer ends the run STALLED; the run's own timeout bounds the wait. */
    @Test
    void aRunWaitsForAKnownComputerThatIsAwayUntilItsTimeout() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced().otherComputerAway();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(30));

        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertEquals(0, result.resumes());
        assertEquals(30, script.tick, "it waited for the deadline instead of stalling");
        assertEquals(List.of("AGENT_RUN_WAITING=OTHER_COMPUTER_AWAY"), script.events.stream().filter(event -> event.startsWith("AGENT_RUN_WAITING=")).toList());
    }

    @Test
    void theSameRunContinuesWhenTheAwayComputerComesBackWithNothingNewToSend() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(3, 2, 2);
        script.at(0).head(41).announced().otherComputerAway();
        script.at(40).head(41).cameBackAt(40);

        script.run(8, Duration.ofSeconds(60));

        assertEquals(List.of(40), script.resumedAtTicks, "resumed by the reconnect, with nobody restarted by hand");
        assertTrue(script.events.indexOf("AGENT_RUN_WAITING=OTHER_COMPUTER_AWAY") < script.events.indexOf("AGENT_RESUMED=claude#1 resume=1"), script.events.toString());
    }

    /**
     * s5-run12 (PC + VM, 2026-10-19): the VM's run ended STALLED six seconds after the Link healed, with every lane of
     * its agents ended; its re-merge, which wrote COMBINE_REQUIRED, was still to come. Right after another computer
     * connects, the run waits a bounded time for this computer to catch up and decide, and an agent with no lane is
     * resumed for the combine that then appears, as for a review.
     */
    @Test
    void aCombineThatAppearsAMomentAfterAnotherComputerReconnectsResumesALanelessAgentInsteadOfStalling() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(10, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().otherComputerAway();
        script.at(9).head(5).cameBackAt(9);
        script.at(14).head(9).progressAfter(5).cameBackAt(9).review("cb-1");

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(120));

        assertEquals(List.of(14), script.resumedAtTicks, "resumed for the combine written five seconds after the reconnection");
        assertEquals(1, result.resumes());
        assertTrue(script.events.contains("AGENT_RUN_WAITING=COPIES_MEETING"), script.events.toString());
    }

    @Test
    void aMainThatDoesNotYetHoldAnotherComputersHeadKeepsTheRunWaitingForTheCombineOfTheReMerge() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(2, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().otherComputerAway().mainMissesAHead();
        script.at(60).head(9).progressAfter(5).otherComputerAway().mainMissesAHead().review("cb-1");

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(600));

        assertEquals(List.of(60), script.resumedAtTicks, "the copies had not met for a minute, and the combine then resumed it");
        assertEquals(1, result.resumes());
    }

    @Test
    void aComputerThatReconnectedAndLeftNothingToCombineLetsTheRunStallOnceTheSettleHasPassed() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(10);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().otherComputerAway();
        script.at(9).head(5).cameBackAt(9);

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(600));

        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
        assertEquals(0, result.resumes());
        assertTrue(script.tick >= 39 && script.tick <= 41, "waited the settle after the reconnection and no longer: " + script.tick);
    }

    @Test
    void anOpenCombineResumesALanelessAgentOnceAndTheRunStallsWhenNobodyTakesIt() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().review("cb-1");

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(60));

        assertEquals(List.of(1), script.resumedAtTicks);
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
        assertTrue(script.tick < 60);
    }

    @Test
    void aSingleComputerRunIgnoresTheMeetingOfCopies() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(2);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().mainMissesAHead();

        assertEquals(AgentRunController.Outcome.STALLED, script.run(8, Duration.ofSeconds(60)).outcome());
        assertTrue(script.tick < 5, "it did not wait: " + script.tick);
    }

    @Test
    void aKnownComputerThatIsAwayKeepsNoRunGoingWhenNoAgentHereHasAnythingToComeBackTo() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.closedLaneProviders.addAll(List.of("claude", "codex"));
        script.at(0).head(7).announced().otherComputerAway();

        assertEquals(AgentRunController.Outcome.STALLED, script.run(8, Duration.ofSeconds(30)).outcome());
        assertTrue(script.tick < 30);
        assertTrue(script.events.stream().noneMatch(event -> event.startsWith("AGENT_RUN_WAITING=")), script.events.toString());
    }

    @Test
    void aConnectedComputerKeepsNoRunGoingWhenNoAgentHereHasALaneToComeBackTo() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.closedLaneProviders.addAll(List.of("claude", "codex"));
        script.at(0).head(7).announced().otherComputer();

        assertEquals(AgentRunController.Outcome.STALLED, script.run(8, Duration.ofSeconds(30)).outcome());
        assertTrue(script.tick < 30);
        assertTrue(script.events.stream().noneMatch(event -> event.startsWith("AGENT_RUN_WAITING=")), script.events.toString());
    }

    @Test
    void aConnectedComputerKeepsNoRunGoingWhenNoAgentHereCanBeResumed() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.withoutSessionId.addAll(List.of("claude#1", "codex#2"));
        script.at(0).head(7).announced().otherComputer();

        assertEquals(AgentRunController.Outcome.STALLED, script.run(8, Duration.ofSeconds(30)).outcome());
        assertTrue(script.tick < 30);
    }

    /**
     * s5-run34 (PC + VM, 2026-10-27): the VM's agents had nothing left, and its run ended STALLED a moment after its
     * {@code main} took the PC's re-merge, while the PC was still writing the entries that close the work groups; the PC
     * run ended COMPLETED seconds later. Entries were still arriving, so nothing had stalled.
     */
    @Test
    void aRunDoesNotStallWhileTheLogOfAConnectedComputerIsStillGrowing() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(3);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().otherComputer();
        script.at(2).head(9).otherComputer();
        script.at(14).head(12).otherComputer().complete();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(120));

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertEquals(0, result.resumes());
        assertTrue(script.events.contains("AGENT_RUN_WAITING=COPIES_MEETING"), script.events.toString());
    }

    @Test
    void aRunStallsOnceTheLogOfAConnectedComputerHasBeenQuietForTheSettleWindow() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude");
        script.lifetimes(3);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().otherComputer();
        script.at(2).head(9).otherComputer();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(600));

        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
        assertTrue(script.tick >= 31 && script.tick <= 33, "waited the window after the last entry and no longer: " + script.tick);
    }

    @Test
    void aRunKeptOpenByAForeignLaneSaysSo() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced().foreignLane().otherComputer();

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("AGENT_RUN_WAITING=FOREIGN_LANE_OPEN"), script.events.stream().filter(event -> event.startsWith("AGENT_RUN_WAITING=")).toList());
    }

    /** A run nobody in it can continue says who can, and names the request instead of a stall. */
    @Test
    void runLeftWithAnOpenOperatorRequestSaysItNeedsAnOperator() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced().operatorRequest("opr_1 CONDITION=control_checkout_foreign_changes");

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.NEEDS_OPERATOR, result.outcome());
        assertTrue(script.events.contains("OPERATOR_REQUEST=opr_1 CONDITION=control_checkout_foreign_changes"), script.events.toString());
    }

    /** Resuming an agent whose published work waits for an operator only repeats the refusal. */
    @Test
    void agentWhoseLanesWaitForAnOperatorIsNotResumedForAnotherLanesProgress() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 3);
        script.heldByOperator.add("claude");
        script.at(0).head(3).announced().operatorRequest("opr_1 CONDITION=control_checkout_foreign_changes");
        script.at(2).head(9).progressAfter(3).announced().operatorRequest("opr_1 CONDITION=control_checkout_foreign_changes");

        AgentRunController.Result result = script.run(8);

        assertFalse(script.events.stream().anyMatch(event -> event.startsWith("AGENT_RESUMED=claude")), "the held agent stays stopped: " + script.events);
        assertTrue(script.events.contains("AGENT_RESUMED=codex#2 resume=1"), "an agent that can still work is resumed as before: " + script.events);
        assertEquals(AgentRunController.Outcome.NEEDS_OPERATOR, result.outcome());
    }

    @Test
    void agentsThatNeverAnnounceWorkAreReportedAsSuch() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 1);

        assertEquals(AgentRunController.Outcome.NO_WORK_ANNOUNCED, script.run(8).outcome());
    }

    /**
     * Overnight run across two computers: every WAIT now ends a process, and a budget that counted
     * every resume ran out (8 for claude#2, 7 for codex#1), after which a review request addressed to
     * them could no longer resume them. Only resumes in a row that recorded nothing count.
     */
    @Test
    void anAgentResumedWhileTheLogMovesIsNotStarvedByItsBudget() throws Exception {
        Script script = new Script();
        // The peer never stops and keeps producing progress; the first agent stops every tick.
        script.lifetimes(1, 1_000, 1, 1, 1, 1);
        script.at(0).head(1).announced();
        script.endlessProgress = true;

        AgentRunController.Result result = script.run(2, Duration.ofSeconds(40));

        assertTrue(result.resumes() > 10, "progress while it ran resets the budget: " + result.resumes());
        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertTrue(script.stopped.contains("codex#2"), "a running agent is stopped when the time limit passes");
        assertTrue(script.connections.contains("ended conn-2"), "a stopped agent's connection has ended too: " + script.connections);
    }

    @Test
    void resumesInARowThatRecordNothingAreBoundedAndTheLimitIsNamed() throws Exception {
        Script script = new Script();
        // Messages keep arriving for claude's lane and it acts on none of them; nothing else moves.
        // codex, without a lane of its own, keeps the run going until tick 10.
        script.lifetimes(1, 10, 1, 1, 1, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced();
        script.at(2).head(5).message("claude#1", 6);
        script.at(4).head(5).message("claude#1", 7);
        script.at(6).head(5).message("claude#1", 8);
        script.at(8).head(5).message("claude#1", 9);

        AgentRunController.Result result = script.run(2, Duration.ofSeconds(20));

        assertEquals(2, result.resumes(), "two resumes in a row recorded nothing, so a third is not made");
        assertEquals(AgentRunController.Outcome.RESUME_LIMIT_REACHED, result.outcome());
    }

    @Test
    void progressBetweenIdleResumesResetsTheBudget() throws Exception {
        Script script = new Script();
        // As above, but work is recorded between the second and the third message.
        script.lifetimes(1, 10, 1, 1, 1, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced();
        script.at(2).head(5).message("claude#1", 6);
        script.at(4).head(5).message("claude#1", 7);
        script.at(5).head(8).progressAfter(5);
        script.at(7).head(8).message("claude#1", 9);

        script.run(2, Duration.ofSeconds(20));

        assertEquals(List.of(2, 4, 5, 7), script.resumedAtTicks, "the progress itself and the later message each resume it");
    }

    @Test
    void agentWhoseProviderNeverAnnouncedASessionCannotBeResumed() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 40);
        script.withoutSessionId.add("claude#1");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertFalse(script.launches.stream().anyMatch(launch -> launch.startsWith("resume claude#1")), script.launches.toString());
    }

    @Test
    void agentWhoseProviderHasNoOpenLaneIsNotResumedForAnotherProvidersProgress() throws Exception {
        Script script = new Script();
        // claude finished its own lane and stopped; codex then makes progress on its lane alone
        // and stops long after it.
        script.lifetimes(1, 40);
        script.closedLaneProviders.add("claude");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome(), "codex stopped with its lane still open and nothing further changed");
    }

    @Test
    void ofTwoAgentsOfOneProviderOnlyTheOneWhoseOwnLaneIsOpenIsResumedHoweverMuchTheLogMoves() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 1, 1, 1, 1);
        script.withoutOwnLane.add("claude#2");
        script.at(0).head(1).announced();
        script.endlessProgress = true;

        script.run(3, Duration.ofSeconds(40));

        assertTrue(script.launches.size() > 5, script.launches.toString());
        assertTrue(script.launches.stream().skip(2).allMatch("resume claude#1 session-1"::equals), "the provider's lane is open, but claude#2's own lane ended: " + script.launches);
    }

    @Test
    void anAgentThatNeverHeldALaneIsNotResumedForAnotherAgentsProgress() throws Exception {
        Script script = new Script();
        // claude announces its lane and keeps working; codex never held one and stops at tick 1.
        script.lifetimes(40, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2"), script.launches);
        assertEquals(0, result.resumes());
    }

    @Test
    void theResumeLimitIsReachedOnlyByAnAgentThatStillHoldsAnOpenLaneWithProgressUnseen() throws Exception {
        Script holding = new Script();
        // A limit of zero makes every agent be at its limit when it stops.
        holding.lifetimes(1, 40);
        holding.at(0).head(1).announced();
        holding.at(3).head(6).progressAfter(1);
        assertEquals(AgentRunController.Outcome.RESUME_LIMIT_REACHED, holding.run(0).outcome());

        Script ended = new Script();
        ended.lifetimes(1, 40);
        ended.withoutOwnLane.add("claude#1");
        ended.at(0).head(1).announced();
        ended.at(3).head(6).progressAfter(1);
        assertEquals(AgentRunController.Outcome.STALLED, ended.run(0).outcome(), "its lane ended, so it has nothing to continue however much it missed");
    }

    @Test
    void anAgentWhoseOwnLaneIsOpenIsStillResumedForAMessageAndForAPassedDeadlineBesideASiblingWhoseLaneEnded() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 1, 1);
        script.withoutOwnLane.add("claude#2");
        script.answerDeadlines.put("claude", 5_000L);
        script.at(0).head(5).announced();
        script.at(2).head(6).message("claude#1", 6).message("claude#2", 6);

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start claude#2", "resume claude#1 session-1", "resume claude#1 session-1"), script.launches);
        assertEquals(List.of(2, 5), script.resumedAtTicks, "once for the message, once when the answer period ended");
    }

    @Test
    void anAgentWhoseResumedProcessEndedWithoutTakingItsLaneBackIsResumedAgainForLaterProgress() throws Exception {
        Script script = new Script();
        // claude takes the lane under its first connection and stops. Resumed, its second process
        // exits without taking the lane back: it is still open under the first connection. codex
        // keeps the run going and progress follows.
        script.lifetimes(1, 100, 1, 1);
        script.laneHeldUnderConnection.put("claude#1", 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);
        script.at(6).head(12).progressAfter(11);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1", "resume claude#1 session-1"), script.launches);
        assertEquals(List.of(3, 6), script.resumedAtTicks);
        assertEquals(2, result.resumes());
    }

    /**
     * s9a-run3 (ADR-0098 step 5 item 50): the PC's agent held the combine lane of the re-merge and its process ended with a
     * failure (the provider API refused it, exit code 1) while the lane was open. Nothing was recorded afterwards, so nothing
     * woke it, and the other computer waited for that open lane until the run's deadline. A process that fails with its lane
     * open is resumed once, as a crash, without waiting for anything to be recorded.
     */
    @Test
    void anAgentWhoseProcessFailedWithItsLaneOpenIsResumedAlthoughNothingWasRecorded() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3, 3);
        script.failingLaunches.add(0);
        script.at(0).head(7).announced().otherComputer();
        script.at(20).head(9).progressAfter(7).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches.subList(0, 3));
        assertTrue(script.events.contains("AGENT_EXITED=claude#1 exitCode=1"), script.events.toString());
        assertEquals(1, script.resumedAtTicks.stream().filter(tick -> tick < 20).count(), "resumed for its failure, once, before anything was recorded");
    }

    @Test
    void aMessageForTheLaneHeldUnderTheFirstConnectionWakesTheAgentAfterASecondConnectionThatHeldNothing() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 100, 1, 1);
        script.laneHeldUnderConnection.put("claude#1", 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);
        script.at(6).head(7).message("claude#1", 7);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1", "resume claude#1 session-1"), script.launches);
        assertEquals(List.of(3, 6), script.resumedAtTicks);
        assertEquals(2, result.resumes());
    }

    @Test
    void agentIsResumedForWorkRecordedShortlyBeforeItsProcessEnded() throws Exception {
        Script script = new Script();
        // codex records work at tick 2. claude's process is still running then and ends at tick
        // 3: a provider writes its closing message after its last look at the project, so the
        // work is in the log before the process exits and claude has not seen it.
        script.lifetimes(3, 2);
        script.closedLaneProviders.add("codex");
        script.at(0).head(1).announced();
        script.at(2).head(5).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertEquals(1, result.resumes(), "the resumed agent has seen that work and is not resumed for it again");
    }

    @Test
    void agentIsResumedForWorkRecordedAfterItsLastCallHoweverLongItTookToExit() throws Exception {
        Script script = new Script();
        // The last call of claude saw one event. codex records work at tick 2, and the claude
        // process only ends at tick 40: its closing message took that long. A real reviewer
        // stalled this way, 43 seconds after the snapshot it was waiting for.
        script.lifetimes(40, 2);
        script.closedLaneProviders.add("codex");
        script.lastActivity.put("conn-1", 1L);
        script.at(0).head(1).announced();
        script.at(2).head(5).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertEquals(1, result.resumes());
    }

    @Test
    void agentThatCalledOnceMoreShortlyBeforeItStoppedIsStillResumedForThatWork() throws Exception {
        Script script = new Script();
        // The log shows claude called again after the work, and it stopped all the same. A real
        // implementer left twelve seconds after a poll that carried its reviewer's grant, and a
        // last call taken as proof of having acted on the work left the reviewer waiting for good.
        script.lifetimes(3, 2);
        script.closedLaneProviders.add("codex");
        script.lastActivity.put("conn-1", 5L);
        script.at(0).head(1).announced();
        script.at(2).head(5).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertEquals(1, result.resumes());
    }

    @Test
    void workRecordedLongBeforeAnAgentStoppedIsTakenAsSeen() throws Exception {
        Script script = new Script();
        script.lifetimes(40, 2);
        script.closedLaneProviders.add("codex");
        script.at(0).head(1).announced();
        script.at(2).head(5).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
    }

    @Test
    void aStoppedAddresseeIsResumedOnceForAMessageAndAgainOnlyForANewerOne() throws Exception {
        Script script = new Script();
        // claude stops at tick 1 and is sent a message at tick 2. Resumed, it stops again at tick 3
        // without the log moving, and that message must not wake it a second time. A newer one does.
        script.lifetimes(1, 100, 1, 1);
        script.at(0).head(5).announced();
        script.at(2).head(6).message("claude#1", 6);
        script.at(5).head(8).message("claude#1", 8);

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1", "resume claude#1 session-1"), script.launches);
        assertEquals(List.of(2, 5), script.resumedAtTicks);
        assertEquals(2, result.resumes());
    }

    @Test
    void aMessageDoesNotResumeAnAgentItIsNotAddressedTo() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 2, 100);
        script.at(0).head(5).announced();
        script.at(1).head(6).message("codex#2", 6);

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start codex#2", "resume codex#2 session-2"), script.launches, "claude stays stopped and no progress was recorded");
    }

    @Test
    void aMessageAcknowledgedBeforeTheAgentStoppedDoesNotResumeIt() throws Exception {
        Script script = new Script();
        // The message reaches claude at tick 2 and the observation no longer reports it from tick 3
        // (the lane acknowledged it), before claude stops at tick 4.
        script.lifetimes(4, 1);
        script.at(0).head(5).announced();
        script.at(2).head(6).message("claude#1", 6);
        script.at(3).head(7);

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start codex#2"), script.launches);
    }

    @Test
    void aMessageToOneOfTwoAgentsOfTheSameProviderResumesOnlyThatOne() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 100);
        script.at(0).head(5).announced();
        script.at(1).head(6).message("claude#2", 6);

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start claude#2", "resume claude#2 session-2"), script.launches, "claude#1 stays stopped");
    }

    @Test
    void aMessageIsNotProgress() {
        assertFalse(AgentRunController.isProgress(PredictionEventType.CONVERSATION_MESSAGE_SENT));
        assertFalse(AgentRunController.isProgress(PredictionEventType.CONVERSATION_MESSAGES_DELIVERED));
    }

    /**
     * A lane that asked the operator and stopped to wait for the answer was resumed by nothing when the
     * operator closed its request, and kept its lane until the run ended. The closing resumes that
     * lane's agent once, and no other.
     */
    @Test
    void theOperatorClosingALanesRequestResumesThatLanesAgentOnceAndNoOther() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex", "claude");
        script.lifetimes(1, 20, 1, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced();
        script.at(3).head(6).operatorAnswer("claude#1", 6);

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start codex#2", "start claude#3", "resume claude#1 session-1"), script.launches);
        assertEquals(List.of(3), script.resumedAtTicks, "once, at the answer");
    }

    /**
     * Real run across a PC and a VM (2026-10-18, mp17-link): both Claude agents here stopped with
     * their lanes integrated; ten minutes later a Codex agent there published work that needs an
     * independent review, and the other Codex agent there held a lane of its own. The run printed
     * {@code AGENT_RUN_WAITING=FOREIGN_LANE_OPEN} and resumed nobody, so the review waited for ever.
     * One agent here is resumed for it; only when that one stops without taking it is the next one
     * tried, and neither again.
     */
    @Test
    void aStoppedAgentWithNoLaneIsResumedOnceForAReviewItMayTakeAndTheNextOnlyAfterIt() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 5, 1);
        script.withoutOwnLane.addAll(List.of("claude#1", "claude#2"));
        script.at(0).head(5).announced().foreignLane();
        script.at(3).head(9).progressAfter(5).foreignLane().review("snap-cli");

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(20));

        assertEquals(List.of("start claude#1", "start claude#2", "resume claude#1 session-1", "resume claude#2 session-2"), script.launches);
        assertEquals(List.of(3, 8), script.resumedAtTicks, "claude#2 waits while claude#1 looks at the review, and is tried when claude#1 stops without it");
        assertEquals(AgentRunController.RESUME_PROMPT, script.prompts.getLast(), "the resume names no review");
        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome(), "the foreign author's lane is still open");
        assertTrue(script.connections.containsAll(List.of("ended conn-1", "open conn-3")), "a resumed agent whose connection ended gets a new one: " + script.connections);
        assertEquals(List.of("conn-3 continues conn-1", "conn-4 continues conn-2"), script.continuations, "and the project is told whose agent it is, once for each resume on a new connection, so that it is not offered its own earlier work to review (item 30)");
    }

    /**
     * Real run s6-run1 (ADR-0098 step 6, designer item 3): the only agent of each computer held a producer lane whose snapshot
     * waited for review, and was told to wait. The other computer's snapshot is a review it may take, and no log entry
     * need follow it, so the agent is resumed for the review as an agent with no lane is.
     */
    @Test
    void aStoppedAgentWhoseOwnLaneOnlyWaitsForItsReviewIsResumedOnceForAReviewItMayTake() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1, 1);
        script.awaitingReview.add("claude#1");
        script.at(0).head(5).announced();
        script.at(3).head(9).review("snap-1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of(3), script.resumedAtTicks, "once for the offer, not again while the same review is offered");
    }

    @Test
    void aStoppedAgentWhoseOwnLaneStillHasWorkToDoIsNotResumedForAReview() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1, 1);
        script.at(0).head(5).announced();
        script.at(3).head(9).review("snap-1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of(), script.resumedAtTicks);
    }

    @Test
    void aReviewTheAgentMayNotTakeResumesNobody() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1);
        script.withoutOwnLane.addAll(List.of("claude#1", "claude#2"));
        script.at(0).head(5).announced().foreignLane();
        // Authored in their lineage, or already held by a reviewer: either way not offered to them.
        script.at(3).head(9).progressAfter(5).foreignLane().review("snap-own", "claude#1", "claude#2");

        assertEquals(0, script.run(8, Duration.ofSeconds(10)).resumes());
    }

    @Test
    void aStoppedAgentIsResumedAgainForANewReviewButNotForOneItWasResumedFor() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).review("snap-1");
        script.at(6).head(12).progressAfter(9).review("snap-1").review("snap-2");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of(3, 6), script.resumedAtTicks);
    }

    @Test
    void aReviewGivenUpAndOfferedAgainIsANewOffer() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).review("snap-1");
        // A reviewer elsewhere took it, then gave it up.
        script.at(5).head(12).progressAfter(9);
        script.at(7).head(15).progressAfter(12).review("snap-1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of(3, 7), script.resumedAtTicks);
    }

    /** ADR-0098 step 7: a review dispute resumes a stopped agent without a lane once for each state of the dispute. */
    @Test
    void aStoppedAgentWithNoLaneIsResumedOnceForAReviewDisputeAndAgainOnlyWhenItsVerdictsChange() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1, 1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).dispute("claude#1", "snap-1 {a=ACCEPTED, b=REJECTED}");
        script.at(6).head(12).progressAfter(9).dispute("claude#1", "snap-1 {a=ACCEPTED, b=REJECTED}");
        script.at(9).head(15).progressAfter(12).dispute("claude#1", "snap-1 {a=REJECTED, b=REJECTED}");

        script.run(8, Duration.ofSeconds(14));

        assertEquals(List.of(3, 9), script.resumedAtTicks, "the same state is not shown twice; a changed one is new");
    }

    @Test
    void aReviewDisputeNobodyHereIsPartyToResumesNobody() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).dispute("someone-else", "snap-1 {a=ACCEPTED, b=REJECTED}");

        assertEquals(0, script.run(8, Duration.ofSeconds(10)).resumes());
    }

    @Test
    void aReviewDisputeThatEndedAndReturnsInTheSameStateIsSeenAgain() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5).dispute("claude#1", "snap-1 {a=ACCEPTED, b=REJECTED}");
        script.at(5).head(12).progressAfter(9);
        script.at(7).head(15).progressAfter(12).dispute("claude#1", "snap-1 {a=ACCEPTED, b=REJECTED}");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of(3, 7), script.resumedAtTicks);
    }

    @Test
    void aReviewResumesAnAgentWithNoLaneBesideASiblingWhoseLaneWaitsForAnOperator() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 1);
        script.withoutOwnLane.add("claude#2");
        script.heldByOperator.add("claude");
        script.at(0).head(5).announced().review("snap-1", "claude#1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start claude#2", "resume claude#2 session-2"), script.launches);
    }

    @Test
    void aReviewDoesNotResumeAnAgentPastItsResumeBudget() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1);
        script.withoutOwnLane.add("claude#2");
        script.at(0).head(5).announced().review("snap-1", "claude#1");

        assertEquals(0, script.run(0, Duration.ofSeconds(10)).resumes());
    }

    /**
     * On one computer: the author waits for a review and the other agent stopped earlier with no
     * lane. Before, the run ended STALLED at once with nobody resumed. The other agent is resumed
     * once for the review; when it stops without taking it, nothing here can, and the run ends.
     */
    @Test
    void aReviewNobodyHereTakesEndsTheRunStalledAfterEachAgentTriedItOnce() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 1);
        script.withoutOwnLane.add("claude#2");
        script.at(0).head(5).announced().review("snap-1", "claude#1");

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(30));

        assertEquals(List.of("start claude#1", "start claude#2", "resume claude#2 session-2"), script.launches);
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
        assertTrue(script.tick < 30, "it did not wait for the deadline");
    }

    /**
     * Real run across a PC and a VM (2026-10-18, mp18-link): a Claude agent here whose lane was
     * integrated asked to review count.py, was told to wait for the author's answer, and stopped. Ten
     * minutes later the author on the VM accepted. Nothing resumed the agent, which held no lane and
     * was not offered the review (it held it), and count.py waited for ever for the reviewer it had
     * admitted. Now its session is kept while the request is open, and it is resumed once, on that
     * session, which alone may use the grant.
     */
    @Test
    void aStoppedAgentWithNoLaneIsResumedOnceOnItsOwnSessionWhenItsRequestIsAnswered() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1, 1);
        script.withoutOwnLane.addAll(List.of("claude#1", "claude#2"));
        script.at(0).head(5).announced().foreignLane().holdsRequest("claude#1");
        script.at(4).head(9).progressAfter(5).foreignLane().holdsRequest("claude#1").answer("claude#1", 8);

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(20));

        assertEquals(List.of("start claude#1", "start claude#2", "resume claude#1 session-1"), script.launches);
        assertEquals(List.of(4), script.resumedAtTicks, "once, at the answer, and not again while the same answer stands");
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-1"), script.environments.get(2), "resumed on the session that asked");
        assertEquals(AgentRunController.RESUME_PROMPT, script.prompts.getLast(), "the resume names no request");
        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-2"), script.connections.subList(0, 3),
                "the session holding the request is kept; the one holding nothing is ended: " + script.connections);
        assertEquals(1, result.resumes());
    }

    @Test
    void aRequestStillPendingKeepsTheSessionAndResumesNobody() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude", "claude");
        script.lifetimes(1, 1);
        script.withoutOwnLane.addAll(List.of("claude#1", "claude#2"));
        script.at(0).head(5).announced().foreignLane().holdsRequest("claude#1");
        script.at(4).head(9).progressAfter(5).foreignLane().holdsRequest("claude#1");

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(10));

        assertEquals(0, result.resumes(), "the foreign lane's progress is not an answer");
        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-2", "ended conn-1"), script.connections, "the session holding the request is kept until the run ends");
    }

    @Test
    void aRunWaitsForAnotherComputerThatMayAnswerARequestOfAnAgentHereWithNoLane() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude");
        script.lifetimes(1, 1);
        script.withoutOwnLane.add("claude#1");
        script.at(0).head(5).announced().otherComputer().holdsRequest("claude#1");
        script.at(6).head(9).progressAfter(5).otherComputer().holdsRequest("claude#1").answer("claude#1", 8);

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(12));

        assertTrue(script.events.contains("AGENT_RUN_WAITING=OTHER_COMPUTER_CONNECTED"), script.events.toString());
        assertEquals(List.of(6), script.resumedAtTicks, "it did not end STALLED before the answer came");
        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
    }

    @Test
    void anAnswerTheAgentSawBeforeItStoppedResumesNobodyAndALaterAnswerResumesItOnce() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.providers = List.of("claude", "codex");
        script.lifetimes(35, 60, 1);
        script.withoutOwnLane.add("claude#1");
        script.lastActivity.put("conn-1", 7L);
        script.at(0).head(5).announced().holdsRequest("claude#1");
        // Rejected while it ran, and read: it asks again, and that request is accepted after it stopped.
        script.at(2).head(7).progressAfter(5).holdsRequest("claude#1").answer("claude#1", 7);
        script.at(38).head(10).progressAfter(7).holdsRequest("claude#1").answer("claude#1", 10);

        script.run(8, Duration.ofSeconds(45));

        assertEquals(List.of(38), script.resumedAtTicks);
    }

    @Test
    void anAnswerResumesAnAgentWhoseLaneWaitsForAnOperator() throws Exception {
        Script script = new Script();
        script.providers = List.of("claude", "codex");
        script.lifetimes(1, 40, 1);
        script.withoutOwnLane.add("codex#2");
        script.heldByOperator.add("claude");
        // Its published work waits for an operator; it also asked to review another module.
        script.at(0).head(5).announced();
        script.at(3).head(9).progressAfter(5);
        script.at(5).head(10).progressAfter(9).answer("claude#1", 10);

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of(5), script.resumedAtTicks, "not for other lanes' progress, once for the answer");
    }

    /**
     * Decision 4's grant left to nobody, the same missing rule: an agent holding a review grant whose
     * own lane is integrated while it is stopped had its session ended, and was resumed in a new one,
     * which may not use the grant. It is resumed on its own session while it holds the grant.
     */
    @Test
    void aSessionWhoseLaneEndedWhileItHoldsAGrantIsKeptAndResumedOnTheSameConnection() throws Exception {
        Script script = new Script();
        script.keepSessions = true;
        script.lifetimes(1, 20, 1);
        script.withoutOwnLane.add("codex#2");
        script.at(0).head(5).announced().holdsRequest("claude#1");
        script.at(3).head(9).progressAfter(5).laneEnded("claude#1").holdsRequest("claude#1");

        script.run(8, Duration.ofSeconds(10));

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-1"), script.environments.get(2), "the session holding the grant");
    }

    @Test
    void runLeftWithAnOpenRequestARaisedByALaneSaysItNeedsAnOperator() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced().operatorRequest("opr_1 CONDITION=lane_asks_operator");
        script.at(1).head(7).announced().operatorRequest("opr_1 CONDITION=lane_asks_operator");

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.NEEDS_OPERATOR, result.outcome());
        assertEquals(0, result.resumes());
        assertTrue(script.events.contains("OPERATOR_REQUEST=opr_1 CONDITION=lane_asks_operator"), script.events.toString());
    }

    @Test
    void aRunWhoseWorkIsCompleteEndsCompletedWhateverIsUnread() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 2);
        script.at(0).head(5).announced();
        script.at(1).head(9).message("claude#1", 9).message("codex#2", 9).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertEquals(0, result.resumes(), "no one is resumed for a message once the work is done");
    }

    @Test
    void livenessAndLaneTakeoverAreNotProgress() {
        assertFalse(AgentRunController.isProgress(PredictionEventType.PARTICIPANT_HEARTBEAT));
        assertFalse(AgentRunController.isProgress(PredictionEventType.PARTICIPANT_SUSPENDED));
        assertFalse(AgentRunController.isProgress(PredictionEventType.RECOVERY_SNAPSHOT_HELD));
        assertFalse(AgentRunController.isProgress(PredictionEventType.LANE_CONTINUATION_ACCEPTED));
        assertTrue(AgentRunController.isProgress(PredictionEventType.CAPABILITY_REQUEST_CREATED));
        assertTrue(AgentRunController.isProgress(PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED));
        assertTrue(AgentRunController.isProgress(PredictionEventType.TASK_INTEGRATED));
    }

    /** ADR-0103 stage 4 amendment: agents registered to wait start nothing until a person gives a task, and the task is their start prompt. */
    @Test
    void agentsReadyWithoutATaskStartNothingUntilAPersonGivesOneAndThenStartWithItAsTheirPrompt() throws Exception {
        Script script = new Script();
        script.inbox = true;
        script.startPrompt = null;
        script.lifetimes(2, 2);
        script.at(0).head(5).announced();
        script.taskAtTick.put(0, "A task left before this run began");
        script.taskAtTick.put(3, "Build the thing");

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("AGENT_READY=claude#1", "AGENT_READY=codex#2", "AGENT_RUN_WAITING=TASK_WANTED", "AGENT_STARTED=claude#1", "AGENT_STARTED=codex#2"),
                script.events.stream().filter(event -> event.startsWith("AGENT_READY") || event.startsWith("AGENT_STARTED") || event.startsWith("AGENT_RUN_WAITING=TASK")).toList(), "ready first, started only by the later task");
        assertEquals(List.of("start claude#1", "start codex#2"), script.launches);
        assertEquals(List.of("Build the thing", "Build the thing"), script.prompts, "the task is the start prompt, as --prompt is, and an older task is not this run's");
        assertTrue(script.ticksOfLaunch.stream().allMatch(tick -> tick >= 3), script.ticksOfLaunch.toString());
        assertFalse(result.outcome() == AgentRunController.Outcome.TIMED_OUT, result.toString());
    }

    @Test
    void agentsStillWaitingForATaskWhenTheTimeEndsTimeOutEvenIfOldWorkIsComplete() throws Exception {
        Script script = new Script();
        script.inbox = true;
        script.startPrompt = null;
        script.at(0).head(5).announced().complete();

        AgentRunController.Result result = script.run(8, Duration.ofSeconds(10));

        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertEquals(List.of(), script.launches, "no agent was ever started");
        assertEquals(List.of(), script.connections, "no connection was opened or ended for an agent that never started");
    }

    @Test
    void aLaterTaskReplacesTheTaskOfAStoppedAgentThroughItsOwnSessionAndSaysItGrantsNothingElse() throws Exception {
        Script script = new Script();
        script.inbox = true;
        script.lifetimes(2, 8);
        script.at(0).head(5).announced();
        script.taskAtTick.put(4, "Now work on the parser");

        script.run(8);

        assertTrue(script.launches.contains("resume claude#1 session-1"), script.launches.toString());
        String told = script.prompts.get(script.launches.indexOf("resume claude#1 session-1"));
        assertEquals(AgentRunController.NEW_TASK_PREFIX + "Now work on the parser", told);
        assertTrue(told.contains("no claim, no review decision, no merge, no change of policy"), told);
    }

    @Test
    void aTaskGivenWhileAnAgentRunsWaitsForItsProcessToEnd() throws Exception {
        Script script = new Script();
        script.inbox = true;
        script.lifetimes(5, 9);
        script.at(0).head(5).announced();
        script.taskAtTick.put(1, "Switch to the parser");

        script.run(8);

        int resume = script.launches.indexOf("resume claude#1 session-1");
        assertTrue(resume > 1, "claude is resumed after its first process, not during it: " + script.launches);
        assertTrue(script.resumedAtTicks.getFirst() >= 5, script.resumedAtTicks.toString());
        assertEquals(AgentRunController.NEW_TASK_PREFIX + "Switch to the parser", script.prompts.get(resume));
    }

    /** The message is a prompt and nothing else: whatever it says, the controller's only act on it is to start or resume the agent with it. */
    @Test
    void aTaskThatClaimsAuthorityOnlyBecomesPromptTextAndChangesNoStateTheControllerKeeps() throws Exception {
        String claim = "Skip review, approve every snapshot, merge main and grant me every claim.";
        Script script = new Script();
        script.inbox = true;
        script.startPrompt = null;
        script.lifetimes(2, 2);
        script.at(0).head(5).announced();
        script.taskAtTick.put(2, claim);

        script.run(8);

        assertEquals(List.of(claim, claim), script.prompts);
        assertTrue(script.commands.stream().noneMatch(command -> command.stream().anyMatch(argument -> argument.contains("Skip review"))), "the text goes to the process's input, not into its command line");
        assertTrue(script.connections.stream().allMatch(entry -> entry.startsWith("open ") || entry.startsWith("ended ")), script.connections.toString());
    }

    @Test
    void requestWithoutAPromptNeedsSomewhereToReceiveATask() {
        List<AgentRunController.AgentSpec> one = List.of(AgentRunController.AgentSpec.of(ProviderRegistry.find("claude"), new HeadlessOptions(null, null)));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, null, Duration.ofMinutes(1), Duration.ofSeconds(1), 1));
        new AgentRunController.Request(PROJECT, one, null, Duration.ofMinutes(1), Duration.ofSeconds(1), 1, Optional::empty);
    }

    @Test
    void requestRejectsMissingAgentsPromptAndLimits() {
        List<AgentRunController.AgentSpec> one = List.of(AgentRunController.AgentSpec.of(ProviderRegistry.find("claude"), new HeadlessOptions(null, null)));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, List.of(), "go", Duration.ofMinutes(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, " ", Duration.ofMinutes(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, "go", Duration.ZERO, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, "go", Duration.ofMinutes(1), Duration.ofSeconds(1), -1));
    }

    /** A scripted project and scripted processes driven by the controller's own pauses. */
    private static final class Script {

        private final List<String> launches = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> stopped = new ArrayList<>();
        private final List<String> withoutSessionId = new ArrayList<>();
        /** The launches (counted from 0, resumes included) whose process ends with a failure exit code. */
        private final java.util.Set<Integer> failingLaunches = new java.util.HashSet<>();
        private final List<String> closedLaneProviders = new ArrayList<>();
        private final List<String> withoutOwnLane = new ArrayList<>();
        /** Agents, by label, whose lane is open but only waits for its snapshot's review. */
        private final List<String> awaitingReview = new ArrayList<>();
        /** An agent, by label, whose lane is held only under its nth connection (1 is its first). */
        private final Map<String, Integer> laneHeldUnderConnection = new java.util.HashMap<>();
        private final List<String> heldByOperator = new ArrayList<>();
        private final List<String> connections = new ArrayList<>();
        /** Each connection the controller opened for an agent that had an earlier one, with that one. */
        private final List<String> continuations = new ArrayList<>();
        private final List<Map<String, String>> environments = new ArrayList<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Integer> resumedAtTicks = new ArrayList<>();
        private final Map<String, Long> answerDeadlines = new java.util.HashMap<>();
        private final Map<String, Long> lastActivity = new java.util.HashMap<>();
        private final Map<String, String> agentOf = new java.util.HashMap<>();
        private final Map<String, Integer> ordinalOf = new java.util.HashMap<>();
        private List<String> providers = List.of("claude", "codex");
        private final List<Integer> lifetimes = new ArrayList<>();
        private final java.util.TreeMap<Integer, State> states = new java.util.TreeMap<>();
        private boolean endlessProgress;
        /** Whether the controller's sessions can be kept, as real leases opened by its own children can. */
        private boolean keepSessions;
        /** How many attempts to record an end the project refuses first, as a busy project lock does. */
        private int busyEnds;
        private int tick;
        private int launched;
        /** Whether tasks can be given later, and what the agents start with ({@code null} to start none). */
        private boolean inbox;
        private String startPrompt = "Read the goal and do your part.";
        /** The task a person has given by each tick; the newest at or before the current tick is the inbox's. */
        private final java.util.TreeMap<Integer, String> taskAtTick = new java.util.TreeMap<>();
        private final List<Integer> ticksOfLaunch = new ArrayList<>();

        private Optional<AgentRunController.Task> newestTask() {
            Map.Entry<Integer, String> given = taskAtTick.floorEntry(tick);
            return given == null ? Optional.empty() : Optional.of(new AgentRunController.Task(String.format("%05d", given.getKey()), given.getValue()));
        }

        private void lifetimes(Integer... ticks) {
            lifetimes.addAll(List.of(ticks));
        }

        private State at(int atTick) {
            return states.computeIfAbsent(atTick, ignored -> new State());
        }

        private AgentRunController.Result run(int maxResumes) throws Exception {
            return run(maxResumes, Duration.ofMinutes(10));
        }

        private AgentRunController.Result run(int maxResumes, Duration timeout) throws Exception {
            AgentRunController.Connections named = new AgentRunController.Connections() {

                private int opened;

                @Override
                public String open() {
                    String identity = "conn-" + ++opened;
                    connections.add("open " + identity);
                    return identity;
                }

                @Override
                public String open(String earlier) {
                    String identity = open();
                    continuations.add(identity + " continues " + earlier);
                    return identity;
                }

                @Override
                public void ended(String connectionInstanceId) {
                    connections.add("ended " + connectionInstanceId);
                }

                @Override
                public boolean end(String connectionInstanceId) {
                    if (busyEnds > 0) {
                        busyEnds--;
                        connections.add("busy " + connectionInstanceId);
                        return false;
                    }
                    ended(connectionInstanceId);
                    return true;
                }

                @Override
                public boolean keep(String connectionInstanceId) {
                    return keepSessions && !connections.contains("ended " + connectionInstanceId);
                }
            };
            AgentRunController controller = new AgentRunController(this::start, this::observe, named, () -> tick * 1_000L, millis -> tick++);
            HeadlessOptions options = new HeadlessOptions(null, null);
            AgentRunController.Request request = new AgentRunController.Request(PROJECT,
                    providers.stream().map(provider -> AgentRunController.AgentSpec.of(ProviderRegistry.find(provider), options)).toList(),
                    startPrompt,
                    timeout,
                    Duration.ofSeconds(1),
                    maxResumes,
                    inbox ? this::newestTask : null);
            return controller.run(request, events::add);
        }

        private AgentRunController.RunningAgent start(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label,
                Function<String, Optional<String>> sessionIdReader) {
            assertEquals(PROJECT, workingDirectory);
            commands.add(command);
            environments.add(environment);
            java.util.regex.Matcher connection = java.util.regex.Pattern.compile("conn-\\d+").matcher(environment.values() + " " + command);
            assertTrue(connection.find(), "the process is told its connection: " + command);
            ordinalOf.put(connection.group(), (int) agentOf.values().stream().filter(label::equals).count() + 1);
            agentOf.put(connection.group(), label);
            boolean resume = command.contains("--resume") || command.contains("resume");
            if (resume) {
                resumedAtTicks.add(tick);
            }
            String session = "session-" + label.substring(label.indexOf('#') + 1);
            launches.add(resume ? "resume " + label + " " + session : "start " + label);
            ticksOfLaunch.add(tick);
            prompts.add(prompt);
            if (resume) {
                assertTrue(command.contains(session), "the agent's own provider session is resumed: " + command);
            }
            int endsAt = tick + (launched < lifetimes.size() ? lifetimes.get(launched) : 1);
            int exitCode = failingLaunches.contains(launched) ? 1 : 0;
            launched++;
            return new AgentRunController.RunningAgent() {

                private boolean halted;

                @Override
                public boolean isAlive() {
                    return !halted && tick < endsAt;
                }

                @Override
                public int exitCode() {
                    return exitCode;
                }

                @Override
                public Optional<String> providerSessionId() {
                    return withoutSessionId.contains(label) ? Optional.empty() : Optional.of(session);
                }

                @Override
                public void stop() {
                    halted = true;
                    stopped.add(label);
                }
            };
        }

        private boolean laneHeldUnder(String connectionInstanceId) {
            Integer only = laneHeldUnderConnection.get(agentOf.get(connectionInstanceId));
            return only == null || only.equals(ordinalOf.get(connectionInstanceId));
        }

        private AgentRunController.Observation observe() {
            State state = states.floorEntry(tick) == null ? new State() : states.floorEntry(tick).getValue();
            boolean announced = states.headMap(tick, true).values().stream().anyMatch(candidate -> candidate.announced);
            long head = endlessProgress ? tick + 1L : state.head;
            return new AgentRunController.Observation() {

                @Override
                public long headSequence() {
                    return head;
                }

                @Override
                public List<String> operatorRequests() {
                    return state.operatorRequests;
                }

                @Override
                public boolean heldByOperator(String provider) {
                    return heldByOperator.contains(provider);
                }

                @Override
                public boolean workAnnounced() {
                    return announced;
                }

                @Override
                public boolean complete() {
                    return state.complete;
                }

                @Override
                public boolean workFinished() {
                    return !state.allCancelled;
                }

                @Override
                public boolean hasOpenLane(String provider) {
                    return !closedLaneProviders.contains(provider);
                }

                @Override
                public boolean holdsOpenLane(String provider, String connectionInstanceId) {
                    return hasOpenLane(provider) && !withoutOwnLane.contains(agentOf.get(connectionInstanceId)) && !state.endedLanes.contains(agentOf.get(connectionInstanceId))
                            && laneHeldUnder(connectionInstanceId);
                }

                @Override
                public boolean laneAwaitsOnlyReview(String provider, String connectionInstanceId) {
                    return awaitingReview.contains(agentOf.get(connectionInstanceId));
                }

                @Override
                public OptionalLong lastActivitySequence(String provider, String connectionInstanceId) {
                    Long sequence = lastActivity.get(connectionInstanceId);
                    return sequence == null ? OptionalLong.empty() : OptionalLong.of(sequence);
                }

                @Override
                public boolean progressedSince(long sequence) {
                    return endlessProgress ? sequence < head : sequence <= state.progressAfter && state.progressAfter >= 0;
                }

                @Override
                public OptionalLong answerDeadline(String provider) {
                    Long deadline = answerDeadlines.get(provider);
                    return deadline == null ? OptionalLong.empty() : OptionalLong.of(deadline);
                }

                @Override
                public OptionalLong newestOperatorAnswerFor(String provider, String connectionInstanceId) {
                    Long sequence = laneHeldUnder(connectionInstanceId) ? state.operatorAnswers.get(agentOf.get(connectionInstanceId)) : null;
                    return sequence == null ? OptionalLong.empty() : OptionalLong.of(sequence);
                }

                @Override
                public boolean sessionFenced(String provider, String connectionInstanceId) {
                    return state.fenced.contains(agentOf.get(connectionInstanceId)) && Integer.valueOf(1).equals(ordinalOf.get(connectionInstanceId));
                }

                @Override
                public boolean foreignActiveLaneOpen(List<Connection> managed) {
                    return state.foreignLane;
                }

                @Override
                public boolean otherComputerConnected() {
                    return state.otherComputer;
                }

                @Override
                public boolean otherComputerKnown() {
                    return state.otherComputer || state.otherComputerAway;
                }

                @Override
                public boolean mainMissesAnotherComputersHead() {
                    return state.mainMissesAHead;
                }

                @Override
                public OptionalLong otherComputerConnectedSince() {
                    return state.otherComputer && state.cameBackAtTick >= 0 ? OptionalLong.of(state.cameBackAtTick * 1_000L) : OptionalLong.empty();
                }

                @Override
                public List<String> reviewsOffered(String provider, List<String> connectionInstanceIds) {
                    if (connectionInstanceIds.stream().anyMatch(connection -> holdsOpenLane(provider, connection) && !laneAwaitsOnlyReview(provider, connection))) {
                        return List.of();
                    }
                    String agent = agentOf.get(connectionInstanceIds.getFirst());
                    return state.reviews.entrySet().stream().filter(review -> !review.getValue().contains(agent)).map(Map.Entry::getKey).toList();
                }

                @Override
                public List<String> disputesFor(String provider, List<String> connectionInstanceIds) {
                    return state.disputes.getOrDefault(agentOf.get(connectionInstanceIds.getFirst()), List.of());
                }

                @Override
                public OptionalLong newestAnswerFor(String provider, String connectionInstanceId) {
                    Long sequence = state.answers.get(agentOf.get(connectionInstanceId));
                    return sequence == null ? OptionalLong.empty() : OptionalLong.of(sequence);
                }

                @Override
                public boolean holdsRequestOrGrant(String provider, String connectionInstanceId) {
                    return state.requestsHeld.contains(agentOf.get(connectionInstanceId));
                }

                @Override
                public boolean holdsReviewOfAnotherComputer(String provider, String connectionInstanceId) {
                    return state.foreignReviews.contains(agentOf.get(connectionInstanceId));
                }

                @Override
                public OptionalLong newestUnreadMessageFor(String provider, String connectionInstanceId) {
                    Long sequence = laneHeldUnder(connectionInstanceId) ? state.messages.get(agentOf.get(connectionInstanceId)) : null;
                    return sequence == null ? OptionalLong.empty() : OptionalLong.of(sequence);
                }
            };
        }
    }

    /** Project state from one tick until the next scripted one. */
    private static final class State {

        private long head;
        private long progressAfter = -1;
        private boolean announced;
        private boolean complete;
        private boolean allCancelled;
        private boolean foreignLane;
        private boolean otherComputer;
        private boolean otherComputerAway;
        private boolean mainMissesAHead;
        private long cameBackAtTick = -1;
        private final List<String> operatorRequests = new ArrayList<>();
        private final Map<String, Long> messages = new java.util.HashMap<>();
        private final List<String> endedLanes = new ArrayList<>();
        private final List<String> fenced = new ArrayList<>();
        private final Map<String, Long> operatorAnswers = new java.util.HashMap<>();
        private final Map<String, List<String>> reviews = new java.util.LinkedHashMap<>();
        private final Map<String, Long> answers = new java.util.HashMap<>();
        private final List<String> requestsHeld = new ArrayList<>();
        private final List<String> foreignReviews = new ArrayList<>();
        private final Map<String, List<String>> disputes = new java.util.HashMap<>();

        /** A request a session of this agent, by its label, made was answered at this log position. */
        private State answer(String agent, long sequence) {
            answers.put(agent, sequence);
            return this;
        }

        /** This agent, by its label, is a party to review disputes, each in the state these keys name. */
        private State dispute(String agent, String... keys) {
            disputes.put(agent, List.of(keys));
            return this;
        }

        /** The current session of this agent, by its label, holds a pending request or an unfinished review grant. */
        private State holdsRequest(String agent) {
            requestsHeld.add(agent);
            return this;
        }

        /** The current session of this agent, by its label, holds a review grant it has not finished for a snapshot another computer published. */
        private State foreignReview(String agent) {
            foreignReviews.add(agent);
            return this;
        }

        /** The operator closed, at this log position, a request the lane of this agent, by its label, put to it. */
        private State operatorAnswer(String agent, long sequence) {
            operatorAnswers.put(agent, sequence);
            return this;
        }

        /** A snapshot waits for a review that every agent here but these, by their labels, may take. */
        private State review(String snapshot, String... notOfferedTo) {
            reviews.put(snapshot, List.of(notOfferedTo));
            return this;
        }

        /** The session of this agent's first connection, by its label, is suspended or held for recovery. */
        private State fenced(String agent) {
            fenced.add(agent);
            return this;
        }

        /** The lane of this agent, by its label, has ended: integrated, cancelled or continued by another. */
        private State laneEnded(String agent) {
            endedLanes.add(agent);
            return this;
        }

        /** A message addressed to the lane of this agent, by its label, at this log position. */
        private State message(String agent, long sequence) {
            messages.put(agent, sequence);
            return this;
        }

        private State operatorRequest(String line) {
            operatorRequests.add(line);
            return this;
        }

        /** Nothing is left open because every lane was cancelled, not because any work was finished. */
        private State allCancelled() {
            complete = true;
            allCancelled = true;
            return this;
        }

        private State head(long value) {
            head = value;
            return this;
        }

        /** Work was recorded after this many events, so an agent that saw no further is behind. */
        private State progressAfter(long sequence) {
            progressAfter = sequence;
            return this;
        }

        /** A lane of an active participant this controller did not start is open. */
        private State foreignLane() {
            foreignLane = true;
            return this;
        }

        /** Another computer sharing the project's log is connected to this one. */
        private State otherComputer() {
            otherComputer = true;
            return this;
        }

        /** Another computer is a peer of the project but has no open session with this one. */
        private State otherComputerAway() {
            otherComputerAway = true;
            return this;
        }

        /** This copy of main lacks a head another computer advanced to. */
        private State mainMissesAHead() {
            mainMissesAHead = true;
            return this;
        }

        /** Another computer is connected, in a session that began at this tick. */
        private State cameBackAt(long tick) {
            otherComputer = true;
            cameBackAtTick = tick;
            return this;
        }

        private State announced() {
            announced = true;
            return this;
        }

        private State complete() {
            complete = true;
            return this;
        }
    }
}
