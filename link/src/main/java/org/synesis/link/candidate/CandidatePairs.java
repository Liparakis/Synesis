package org.synesis.link.candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bounded compatibility filtering and deterministic pair ranking.
 */
public final class CandidatePairs {

  private CandidatePairs() {
  }

  /**
   * Pairs each remote endpoint, in the order given, with the first local candidate of its address
   * family, and leaves out a remote endpoint this machine has no address of the family for.
   *
   * <p>This is the pairing for a dial from one wildcard-bound socket, where the local candidate
   * names the family and does not select a route: a pair per local address would attempt the same
   * destination repeatedly and crowd other destinations out of a bounded race.
   *
   * @param local  local candidates
   * @param remote remote endpoints in the order to try them
   * @return at most one pair per remote endpoint, in the order of {@code remote}
   */
  public static List<CandidatePair> onePerRemote(List<Candidate> local, List<Candidate> remote) {
    List<CandidatePair> pairs = new ArrayList<>();
    for (Candidate right : remote) {
      local.stream()
          .filter(left -> left.type() != CandidateType.RELAY
              && left.address().getAddress().length == right.address().getAddress().length)
          .findFirst()
          .ifPresent(left -> pairs.add(new CandidatePair(left, right)));
    }
    return List.copyOf(pairs);
  }

  /**
   * Generates only same-family non-relay pairs and ranks them deterministically.
   *
   * @param local   local candidates
   * @param remote  remote descriptor candidates
   * @param maximum maximum retained pairs
   * @return stable ranked compatible pairs
   */
  @SuppressWarnings("ExtractMethodRecommender")
  public static List<CandidatePair> generate(List<Candidate> local, List<Candidate> remote,
      int maximum) {
    if (maximum < 1) {
      throw new IllegalArgumentException("maximum pairs must be positive");
    }
    List<CandidatePair> pairs = new ArrayList<>();
    for (Candidate left : local) {
      for (Candidate right : remote) {
        if (left.type() == CandidateType.RELAY || right.type() == CandidateType.RELAY) {
          continue;
        }
        if (left.address()
            .getAddress().length != right.address()
            .getAddress().length) {
          continue;
        }
        pairs.add(new CandidatePair(left, right));
      }
    }
    pairs.sort(Comparator.comparingLong((CandidatePair pair) -> (long) pair.local()
            .priority()
            + pair.remote()
            .priority())
        .thenComparingInt(pair -> pair.local()
            .type()
            .ordinal())
        .thenComparing(CandidatePair::identifier));
    return List.copyOf(pairs.subList(0, Math.min(maximum, pairs.size())));
  }
}
