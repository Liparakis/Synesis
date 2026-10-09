package org.synesis.link.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateGatherer;
import org.synesis.link.candidate.CandidateGatheringPolicy;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.LocalInterfaceCandidateProvider;

/**
 * Bounded local candidate gathering shared by every host and join path of {@link Onboarding}.
 *
 * <p>This is separate from {@link Onboarding} because the gathering policy selection and the
 * ten-second bound do not depend on which onboarding flow is running or on any facade state.
 */
final class LocalCandidateGathering {

  private LocalCandidateGathering() {
  }

  static List<Candidate> gather(String id, int port,
      List<CandidateProvider> optionalProviders)
      throws Exception {
    List<CandidateProvider> providers = new ArrayList<>();
    providers.add(new LocalInterfaceCandidateProvider(id, port, 0));
    providers.addAll(optionalProviders);
    CandidateGatheringPolicy policy = optionalProviders.isEmpty()
        ? CandidateGatheringPolicy.defaults()
        : CandidateGatheringPolicy.directTraversalDefaults();
    try (CandidateGatherer gatherer = new CandidateGatherer(policy)) {
      return gatherer.gather(providers)
          .completion()
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS)
          .candidates();
    }
  }
}
