package org.synesis.coordination.domain.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.coordination.WireGolden.bytes;
import static org.synesis.coordination.WireGolden.edges;
import static org.synesis.coordination.WireGolden.hex;
import static org.synesis.coordination.WireGolden.id;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.prediction.PredictionEventType;

/**
 * Pins the signed command bytes. The vectors were produced once with a throwaway key, so a codec change cannot
 * silently re-sign them.
 */
class CoordinationCommandWireTest {

    private static final String ANONYMOUS = "5343433100000001123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba9876543030000000e00000044736c312d64323062373564353334313562353739643833663964656163323035316634646463373761373737323965303536633265636639373865343131363430373233000000030102030000002c302a300506032b65700321008c92645dd5a458347cd213dc6412d3beeb1cea3268e1cfcf1d9c887dabe9e9c200000040beeecbc996929498cc3c7823e6ffed4e4bdc32485b90cbb9a85a8f877923a302286836f68c9af48bdf7cb9301f1c9e6e9e1cb4b2f57b33347ed1c34d82461e0c";
    private static final String ACTING_AS = "5343433100000002123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba9876543030000000f00000044736c312d64323062373564353334313562353739643833663964656163323035316634646463373761373737323965303536633265636639373865343131363430373233000000067375702dce940000000677726b2dc3a90000000204050000002c302a300506032b65700321008c92645dd5a458347cd213dc6412d3beeb1cea3268e1cfcf1d9c887dabe9e9c200000040f0aeddcb8d0d534a693477c5c5f9e093339508488b4fd32bfb935a618c675a5f3cf421e39ef6c9d79206e99b5efec4473cdb32304708532509bb8193d773f904";

    @Test
    void commandWithoutLogicalActor() throws Exception {
        CoordinationCommand command = CoordinationCommand.decode(bytes(ANONYMOUS));

        assertEquals(id(1), command.identity().value());
        assertEquals(id(2), command.projectIdentity().value());
        assertEquals(id(3), command.predictionId());
        assertEquals(PredictionEventType.TASK_CREATED, command.type());
        assertEquals(Optional.empty(), command.actorSupervisor());
        assertArrayEquals(new byte[]{1, 2, 3}, command.payload());
        assertTrue(command.verify());
        assertEquals(ANONYMOUS, hex(command.encoded()));
        assertEquals("trailing=CoordinationException: trailing command bytes | truncated=CoordinationException: truncated command", edges(CoordinationCommand::decode, bytes(ANONYMOUS)), "anonymous edges");
    }

    @Test
    void commandActingAsSupervisorAndWorker() throws Exception {
        CoordinationCommand command = CoordinationCommand.decode(bytes(ACTING_AS));

        assertEquals(PredictionEventType.TASK_CLAIMED, command.type());
        assertEquals("sup-Δ", command.actorSupervisorId());
        assertEquals("wrk-é", command.actorWorkerId());
        assertTrue(command.verify());
        assertEquals(ACTING_AS, hex(command.encoded()));
        assertEquals("trailing=CoordinationException: trailing command bytes | truncated=CoordinationException: truncated command", edges(CoordinationCommand::decode, bytes(ACTING_AS)), "actingAs edges");
    }
}
