package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.ClaimConflict;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * ADR-0098 step 4, fitness function "Order does not decide": three computers write their own logs, each judging its
 * own entries against what it has seen of the others so far, and then the three logs are applied to fresh copies in
 * many random arrival orders, each log in its own order, answers before requests included. Every decision is the
 * same in every order and on every computer: which lanes stand and overlap, operator requests, work-group status,
 * and which entries counted for nothing.
 */
final class ArrivalOrderPropertyTest {

    private static final UUID PROJECT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final List<String> PATHS = List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt");
    private static final int SEEDS = 40;
    private static final int STEPS = 120;
    private static final int ORDERS = 12;

    @Test
    void everyArrivalOrderGivesTheSameDecisions() throws Exception {
        int overlapping = 0;
        int answeredApart = 0;
        for (int seed = 1; seed <= SEEDS; seed++) {
            Random random = new Random(seed);
            List<Computer> computers = List.of(new Computer(), new Computer(), new Computer());
            for (int step = 0; step < STEPS; step++) {
                Computer computer = computers.get(random.nextInt(computers.size()));
                if (random.nextInt(3) == 0) {
                    Computer other = computers.get(random.nextInt(computers.size()));
                    if (other != computer) {
                        computer.receive(other, 1 + random.nextInt(4));
                    }
                } else {
                    computer.write(random);
                }
            }
            for (Computer computer : computers) {
                for (Computer other : computers) {
                    if (other != computer) {
                        computer.receive(other, Integer.MAX_VALUE);
                    }
                }
            }
            Map<String, List<PredictionEvent>> logs = new TreeMap<>();
            for (Computer computer : computers) {
                logs.put(computer.node(), computer.log);
            }
            String expected = Fingerprint.of(computers.getFirst().view, computers.getFirst().voided);
            if (!expected.contains("overlaps=[]")) {
                overlapping++;
            }
            if (logs.values().stream().filter(log -> log.stream()
                    .anyMatch(entry -> entry.type() == PredictionEventType.OPERATOR_REQUEST_CLOSED)).count() > 1) {
                answeredApart++;
            }
            for (Computer computer : computers) {
                assertEquals(expected, Fingerprint.of(computer.view, computer.voided),
                        "seed " + seed + ": the computers end differently");
            }
            for (int order = 0; order < ORDERS; order++) {
                assertEquals(expected, replayInRandomOrder(logs, random), "seed " + seed + ", order " + order);
            }
        }
        // The cases the property is about did happen: claims made apart that overlap, requests answered apart.
        assertTrue(overlapping > 0, "no seed made overlapping claims apart");
        assertTrue(answeredApart > 0, "no seed answered requests on two computers");
    }

    /** Applies every log to a fresh copy in a random interleaving, a random number of entries of one log at a time. */
    private static String replayInRandomOrder(Map<String, List<PredictionEvent>> logs, Random random)
            throws Exception {
        Projections fresh = new Projections(new ProjectId(PROJECT));
        Map<String, Integer> delivered = new TreeMap<>();
        Map<String, Integer> taken = new TreeMap<>();
        logs.keySet().forEach(log -> {
            delivered.put(log, 0);
            taken.put(log, 0);
        });
        List<String> voided = new ArrayList<>();
        long revision = 0;
        while (delivered.entrySet().stream().anyMatch(entry -> entry.getValue() < logs.get(entry.getKey()).size())) {
            List<String> open = delivered.entrySet().stream()
                    .filter(entry -> entry.getValue() < logs.get(entry.getKey()).size()).map(Map.Entry::getKey).toList();
            String log = open.get(random.nextInt(open.size()));
            delivered.put(log, Math.min(logs.get(log).size(), delivered.get(log) + 1 + random.nextInt(5)));
            revision = take(fresh, logs, delivered, taken, voided, revision);
        }
        return Fingerprint.of(fresh, voided);
    }

    private static long take(Projections projections, Map<String, List<PredictionEvent>> logs,
            Map<String, Integer> delivered, Map<String, Integer> taken, List<String> voided, long revision)
            throws Exception {
        Map<String, List<PredictionEvent>> pending = new LinkedHashMap<>();
        for (String log : logs.keySet()) {
            if (taken.get(log) < delivered.get(log)) {
                pending.put(log, logs.get(log).subList(taken.get(log), delivered.get(log)));
            }
        }
        ArrivalOrder.Round round = ArrivalOrder.take(projections, pending, null, revision);
        long next = revision;
        for (ArrivalOrder.Taken entry : round.taken()) {
            taken.merge(entry.log(), 1, Integer::sum);
            if (entry.code().isEmpty()) {
                next++;
            } else {
                voided.add(entry.log() + ":" + entry.event().sequence() + ":" + entry.code());
            }
        }
        return next;
    }

    /** One computer: its own log, and its view of every log it has seen. */
    private static final class Computer {

        private final NodeIdentity identity;
        private final Projections view = new Projections(new ProjectId(PROJECT));
        private final List<PredictionEvent> log = new ArrayList<>();
        private final Map<Computer, Integer> received = new LinkedHashMap<>();
        private final Map<String, Integer> taken = new TreeMap<>();
        private final List<String> voided = new ArrayList<>();
        private final List<WorkIntent> ownLanes = new ArrayList<>();
        private long revision;
        private int lanes;

        private Computer() throws Exception {
            identity = NodeIdentity.generate();
        }

        private String node() {
            return identity.nodeId();
        }

        /** Takes up to {@code count} more entries of another computer's log, as they arrive here. */
        private void receive(Computer other, int count) throws Exception {
            int from = received.getOrDefault(other, 0);
            int to = (int) Math.min(other.log.size(), (long) from + count);
            received.put(other, to);
            Map<String, List<PredictionEvent>> logs = new TreeMap<>();
            Map<String, Integer> delivered = new TreeMap<>();
            for (Map.Entry<Computer, Integer> seen : received.entrySet()) {
                logs.put(seen.getKey().node(), seen.getKey().log);
                delivered.put(seen.getKey().node(), seen.getValue());
                taken.putIfAbsent(seen.getKey().node(), 0);
            }
            revision = take(view, logs, delivered, taken, voided, revision);
        }

        /** Writes one entry its own view accepts, chosen at random; nothing when the view refuses it. */
        private void write(Random random) throws Exception {
            int kind = random.nextInt(7);
            byte[] payload;
            PredictionEventType type;
            UUID prediction = UUID.randomUUID();
            if (view.workGroupProjection().group(GROUP).isEmpty()) {
                type = PredictionEventType.WORK_GROUP_CREATED;
                prediction = GROUP;
                payload = CollaborationCodec.encodeWorkGroup(new WorkGroup(GROUP, PROJECT, "goal", "accepted", 1,
                        WorkGroup.Status.ACTIVE));
            } else if (kind <= 2) {
                lanes++;
                List<ResourceSelector> paths = new ArrayList<>();
                paths.add(ResourceSelector.pathExact(PATHS.get(random.nextInt(PATHS.size()))));
                WorkIntent lane = new WorkIntent(UUID.randomUUID(), PROJECT, "agt_" + node().substring(0, 8) + "_" + lanes,
                        "codex", UUID.randomUUID(), "goal", "accepted", "base", paths, 1, GROUP, UUID.randomUUID(),
                        WorkIntent.Status.ANNOUNCED);
                type = PredictionEventType.WORK_INTENT_ANNOUNCED;
                prediction = lane.intentId();
                payload = CollaborationCodec.encodeIntent(lane);
                if (append(type, prediction, payload)) {
                    ownLanes.add(lane);
                }
                return;
            } else if (kind == 3 && !ownLanes.isEmpty()) {
                WorkIntent lane = ownLanes.remove(random.nextInt(ownLanes.size()));
                type = PredictionEventType.WORK_INTENT_RELEASED;
                prediction = lane.intentId();
                payload = CollaborationCodec.encodeRelease(lane.intentId());
            } else if (kind == 4) {
                type = PredictionEventType.OPERATOR_REQUEST_RAISED;
                payload = OperatorRequest.raised("opr_" + UUID.randomUUID().toString().substring(0, 8),
                        "condition_" + random.nextInt(2), "summary", "evidence").encodeRaised();
            } else if (kind == 5 && !view.operatorRequests().all().isEmpty()) {
                List<OperatorRequest> all = view.operatorRequests().all();
                type = PredictionEventType.OPERATOR_REQUEST_CLOSED;
                payload = OperatorRequest.encodeClosed(all.get(random.nextInt(all.size())).id(),
                        random.nextBoolean() ? "fixed" : "not a problem");
            } else {
                WorkGroup group = view.workGroupProjection().group(GROUP).orElseThrow();
                type = PredictionEventType.WORK_GROUP_STATUS_CHANGED;
                prediction = GROUP;
                payload = CollaborationCodec.encodeWorkGroup(new WorkGroup(GROUP, PROJECT, group.goal(),
                        group.acceptance(), group.version() + 1,
                        random.nextBoolean() ? WorkGroup.Status.COMPLETED : WorkGroup.Status.ACTIVE,
                        group.reviewPolicy()));
            }
            append(type, prediction, payload);
        }

        private boolean append(PredictionEventType type, UUID prediction, byte[] payload) throws Exception {
            byte[] previous = log.isEmpty() ? new byte[32] : log.getLast().digest();
            PredictionEvent event = PredictionEvent.create(PROJECT, prediction, log.size() + 1L, type, node(), payload,
                    previous, identity, log.size() + 1L);
            try {
                view.check(event, false, revision + 1);
            } catch (java.io.IOException refused) {
                return false;
            }
            view.apply(event, ++revision);
            log.add(event);
            return true;
        }
    }

    /** The decisions every copy must agree on, as text. */
    private static final class Fingerprint {

        private static String of(Projections projections, List<String> voided) {
            TreeSet<String> lanes = new TreeSet<>();
            projections.collaborationProjection().activeIntents().forEach(lane -> lanes.add(lane.intentId().toString()));
            TreeSet<String> overlaps = new TreeSet<>();
            for (WorkIntent lane : projections.collaborationProjection().activeIntents()) {
                for (ClaimConflict conflict : projections.collaborationProjection().conflicts(lane.selectors(),
                        lane.identity())) {
                    overlaps.add(lane.intentId() + "~" + conflict.intentId());
                }
            }
            TreeMap<String, String> requests = new TreeMap<>();
            projections.operatorRequests().all().forEach(request -> requests.put(request.id(), request.state().name()));
            TreeMap<String, String> groups = new TreeMap<>();
            projections.workGroupProjection().groups().forEach(group -> groups.put(group.workGroupId().toString(),
                    group.version() + " " + group.status() + " " + group.goal()));
            assertTrue(voided.stream().noneMatch(String::isEmpty));
            return "lanes=" + lanes + "\noverlaps=" + overlaps + "\nrequests=" + requests + "\ngroups=" + groups
                    + "\nvoided=" + new TreeSet<>(voided);
        }
    }
}
