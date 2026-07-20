package org.synesis.link.candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded compatibility filtering and deterministic pair ranking. */
public final class CandidatePairs {
    private CandidatePairs() { }

    /**
     * Generates only same-family non-relay pairs and ranks them deterministically.
     *
     * @param local local candidates
     * @param remote remote descriptor candidates
     * @param maximum maximum retained pairs
     * @return stable ranked compatible pairs
     */
    public static List<CandidatePair> generate(List<Candidate> local, List<Candidate> remote, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum pairs must be positive");
        List<CandidatePair> pairs = new ArrayList<>();
        for (Candidate left : local) for (Candidate right : remote) {
            if (left.type() == CandidateType.RELAY || right.type() == CandidateType.RELAY) continue;
            if (left.address().getAddress().length != right.address().getAddress().length) continue;
            pairs.add(new CandidatePair(left, right));
        }
        pairs.sort(Comparator.comparingLong((CandidatePair pair) -> (long) pair.local().priority()
                        + pair.remote().priority())
                .thenComparingInt(pair -> pair.local().type().ordinal())
                .thenComparing(CandidatePair::identifier));
        return List.copyOf(pairs.subList(0, Math.min(maximum, pairs.size())));
    }
}
