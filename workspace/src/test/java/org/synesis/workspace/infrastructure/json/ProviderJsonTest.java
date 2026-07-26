package org.synesis.workspace.infrastructure.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderJsonTest {

    @Test
    void preservesIntegralJsonNumbersForJsonRpcIdentifiers() {
        Object parsed = ProviderJson.parse("{\"id\":0,\"fraction\":1.5}");

        Map<?, ?> object = assertInstanceOf(Map.class, parsed);
        assertInstanceOf(Long.class, object.get("id"));
        assertEquals("{\"id\":0,\"fraction\":1.5}", ProviderJson.write(object));
    }
}
