package org.synesis.coordination.persistence;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.synesis.coordination.domain.prediction.PredictionEvent;

/**
 * Takes the entries of other computers' logs that wait on this computer, in an order they can be applied (ADR-0098
 * step 4). Each log is taken in its own order. An entry that names something not seen yet holds back the rest of its
 * own log, which may build on it, and the other logs go on; every entry taken may be what a held one waits for, so
 * the held logs are tried again until nothing moves.
 */
final class ArrivalOrder {

    /**
     * One entry taken.
     *
     * @param log   the node whose log it is
     * @param event the entry
     * @param code  empty when it applied; the rule it broke when it counts for nothing
     */
    record Taken(String log, PredictionEvent event, String code) {
    }

    /**
     * The first entry of a log that waits, and what for.
     *
     * @param log      the node whose log it is
     * @param sequence the entry's position in that log
     * @param code     the code of what it names and has not arrived
     */
    record Waiting(String log, long sequence, String code) {
    }

    /**
     * What one round took and what still waits.
     *
     * @param taken   the entries taken, in the order they were applied
     * @param waiting the first waiting entry of each log that is held back
     */
    record Round(List<Taken> taken, List<Waiting> waiting) {
    }

    private ArrivalOrder() {
    }

    /**
     * Applies every entry that can be applied now.
     *
     * @param projections the projections they are applied to
     * @param pending     each log's entries not taken yet, in log order, by the node whose log it is
     * @param leaderNode  the owner's computer, whose entries keep their exemption until ADR-0098 step 5; null on the
     *                    owner's computer itself, where no arriving entry is its own
     * @param revision    the local revision of the last entry that took effect before this round
     * @return what was taken and what still waits
     * @throws IOException when an entry that passed its trial still fails to apply, a defect of this build
     */
    static Round take(Projections projections, Map<String, List<PredictionEvent>> pending, String leaderNode,
            long revision) throws IOException {
        long next = revision + 1L;
        Map<String, Deque<PredictionEvent>> queues = new TreeMap<>();
        pending.forEach((log, entries) -> queues.put(log, new ArrayDeque<>(entries)));
        List<Taken> taken = new ArrayList<>();
        Map<String, Waiting> waiting = new LinkedHashMap<>();
        boolean moved = true;
        while (moved) {
            moved = false;
            waiting.clear();
            for (Map.Entry<String, Deque<PredictionEvent>> queue : queues.entrySet()) {
                Deque<PredictionEvent> entries = queue.getValue();
                while (!entries.isEmpty()) {
                    PredictionEvent entry = entries.peekFirst();
                    Projections.Arrival arrival = projections.arrive(entry,
                            leaderNode != null && leaderNode.equals(entry.actorNodeId()), next);
                    if (arrival.held()) {
                        waiting.put(queue.getKey(), new Waiting(queue.getKey(), entry.sequence(), arrival.code()));
                        break;
                    }
                    entries.removeFirst();
                    taken.add(new Taken(queue.getKey(), entry, arrival.code()));
                    if (arrival.applied()) {
                        next++;
                    }
                    moved = true;
                }
            }
        }
        return new Round(List.copyOf(taken), List.copyOf(waiting.values()));
    }
}
