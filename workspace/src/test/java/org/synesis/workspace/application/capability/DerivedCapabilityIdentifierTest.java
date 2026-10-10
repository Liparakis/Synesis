package org.synesis.workspace.application.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the owner lineage derived for a capability request whose requester has no lane. */
class DerivedCapabilityIdentifierTest {

  @Test
  void unscopedOwnerLineageIsNameUuidOfTheOwnerNode() {
    assertEquals(
        UUID.fromString("b3550635-1e5b-3ce9-9184-aab867bc9943"),
        CapabilityRequestService.unscopedOwnerLineage("node-1"));
  }
}
