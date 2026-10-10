package org.synesis.coordination.domain.ownership;

import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the persisted bytes of an ownership claim.
 */
class OwnershipClaimWireTest {

    @Test
    void ownershipClaim() throws Exception {
        OwnershipClaim claim = new OwnershipClaim(id(1),
                "domain.persistence",
                "node-é",
                "sup-Δ",
                List.of("src/a/**", "src/é/**"),
                6);
        pin("ownershipClaim", "534f433100000001123456789abcdef00fedcba98765430100000012646f6d61696e2e70657273697374656e6365000000076e6f64652dc3a9000000067375702dce9400000002000000087372632f612f2a2a000000097372632fc3a92f2a2a0000000000000006", "trailing=CoordinationException: trailing ownership bytes | truncated=CoordinationException: malformed ownership claim <- EOFException", claim, OwnershipClaim::encoded, OwnershipClaim::decode);
    }
}
