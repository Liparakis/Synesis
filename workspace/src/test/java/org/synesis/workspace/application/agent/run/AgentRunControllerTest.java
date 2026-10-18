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
        private final List<String> closedLaneProviders = new ArrayList<>();
        private final List<String> withoutOwnLane = new ArrayList<>();
        /** An agent, by label, whose lane is held only under its nth connection (1 is its first). */
        private final Map<String, Integer> laneHeldUnderConnection = new java.util.HashMap<>();
        private final List<String> heldByOperator = new ArrayList<>();
        private final List<String> connections = new ArrayList<>();
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
                    "Read the goal and do your part.",
                    timeout,
                    Duration.ofSeconds(1),
                    maxResumes);
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
            prompts.add(prompt);
            if (resume) {
                assertTrue(command.contains(session), "the agent's own provider session is resumed: " + command);
            }
            int endsAt = tick + (launched < lifetimes.size() ? lifetimes.get(launched) : 1);
            launched++;
            return new AgentRunController.RunningAgent() {

                private boolean halted;

                @Override
                public boolean isAlive() {
                    return !halted && tick < endsAt;
                }

                @Override
                public int exitCode() {
                    return 0;
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
                public List<String> reviewsOffered(String provider, List<String> connectionInstanceIds) {
                    if (connectionInstanceIds.stream().anyMatch(connection -> holdsOpenLane(provider, connection))) {
                        return List.of();
                    }
                    String agent = agentOf.get(connectionInstanceIds.getFirst());
                    return state.reviews.entrySet().stream().filter(review -> !review.getValue().contains(agent)).map(Map.Entry::getKey).toList();
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
        private final List<String> operatorRequests = new ArrayList<>();
        private final Map<String, Long> messages = new java.util.HashMap<>();
        private final List<String> endedLanes = new ArrayList<>();
        private final List<String> fenced = new ArrayList<>();
        private final Map<String, Long> operatorAnswers = new java.util.HashMap<>();
        private final Map<String, List<String>> reviews = new java.util.LinkedHashMap<>();

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
