package org.synesis.coordination.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.ACCEPTED;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.AWAITING_OWNER;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTING;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.REVISION_REQUESTED;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.VALIDATED;
import static org.synesis.coordination.domain.capability.CapabilityLifecycleState.VALIDATING;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Pins the exact membership of the state sets the workspace layer asks about. */
class CapabilityLifecycleStateSetsTest {

  private static Set<CapabilityLifecycleState> membersOf(
      Predicate<CapabilityLifecycleState> predicate) {
    Set<CapabilityLifecycleState> members = EnumSet.noneOf(CapabilityLifecycleState.class);
    for (CapabilityLifecycleState state : CapabilityLifecycleState.values()) {
      if (predicate.test(state)) {
        members.add(state);
      }
    }
    return members;
  }

  @Test
  void hasAcceptedContractCoversAcceptanceThroughValidation() {
    assertEquals(
        EnumSet.of(ACCEPTED, IMPLEMENTING, IMPLEMENTATION_AVAILABLE, VALIDATING, VALIDATED),
        membersOf(CapabilityLifecycleState::hasAcceptedContract));
  }

  @Test
  void blocksRequesterCompletionLeavesOutAcceptedAndValidated() {
    assertEquals(
        EnumSet.of(
            AWAITING_OWNER, REVISION_REQUESTED, IMPLEMENTING, IMPLEMENTATION_AVAILABLE, VALIDATING),
        membersOf(CapabilityLifecycleState::blocksRequesterCompletion));
  }
}
