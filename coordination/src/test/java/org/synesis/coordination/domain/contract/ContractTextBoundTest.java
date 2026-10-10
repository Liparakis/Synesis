package org.synesis.coordination.domain.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.synesis.coordination.WireGolden.id;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * Every text the contract codec writes is bounded where the value is supplied, not only inside the encoder.
 */
class ContractTextBoundTest {

    private static final String TOO_LONG = "x".repeat(32768 + 1);

    @Test
    void oversizedOwnerSelectorAndParticipantAreArgumentErrors() {
        assertThrows(CoordinationArgumentException.class,
                () -> ContractRecord.create(id(1), id(2), TOO_LONG, "body", List.of()));
        assertThrows(CoordinationArgumentException.class,
                () -> ContractRecord.create(id(1), id(2), "owner", "body", List.of(TOO_LONG)));
        assertThrows(CoordinationArgumentException.class,
                () -> new ContractDependency(id(1), TOO_LONG, id(2), 1, ContractDependency.State.ACCEPTED));
    }
}
