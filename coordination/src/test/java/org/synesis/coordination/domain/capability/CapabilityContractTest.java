package org.synesis.coordination.domain.capability;




import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityContractTest {

    @Test
    void acceptsValidBoundedContract() {
        CapabilityContract contract = new CapabilityContract(
                "UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product", "Return empty when missing"),
                List.of("existing product returned", "missing product returns empty")
        );

        assertEquals("UUID productId", contract.inputs());
        assertEquals("Optional<Product>", contract.output());
        assertEquals(2, contract.requiredBehavior().size());
        assertEquals(2, contract.acceptanceTests().size());
    }

    @Test
    void testsContractEquivalence() {
        CapabilityContract c1 = new CapabilityContract(
                "UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product"),
                List.of("existing product returned")
        );
        CapabilityContract c2 = new CapabilityContract(
                "UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product"),
                List.of("existing product returned")
        );
        CapabilityContract c3 = new CapabilityContract(
                "String productId",
                "Optional<Product>",
                List.of("Return exact matching product"),
                List.of("existing product returned")
        );

        assertTrue(c1.isEquivalent(c2));
        assertFalse(c1.isEquivalent(c3));
        assertFalse(c1.isEquivalent(null));
    }

    @Test
    void rejectsBlankInputsOrOutput() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityContract("", "Optional<Product>", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityContract("UUID productId", "   ", List.of(), List.of()));
    }

    @Test
    void rejectsOversizedInputsOrOutput() {
        String longText = "a".repeat(2001);
        assertThrows(IllegalArgumentException.class, () -> new CapabilityContract(longText, "Optional<Product>", List.of(), List.of()));
    }

    @Test
    void rejectsTooManyListItems() {
        List<String> items = java.util.Collections.nCopies(17, "item");
        assertThrows(IllegalArgumentException.class, () -> new CapabilityContract("UUID id", "Optional<P>", items, List.of()));
    }
}
