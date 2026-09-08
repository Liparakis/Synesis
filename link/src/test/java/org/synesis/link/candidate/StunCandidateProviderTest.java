package org.synesis.link.candidate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** Verifies bounded RFC 8489 binding messages and provider behavior. */
final class StunCandidateProviderTest {

    @Test
    void createsBindingRequestAndExtractsXorMappedAddress() throws Exception {
        byte[] transactionId = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        byte[] request = StunBindingMessage.bindingRequest(transactionId);
        assertEquals(20, request.length);
        assertEquals(0x0001, ByteBuffer.wrap(request).getShort() & 0xffff);
        assertArrayEquals(transactionId, java.util.Arrays.copyOfRange(request, 8, 20));

        InetAddress mapped = InetAddress.getByName("203.0.113.7");
        byte[] response = successResponse(transactionId, mapped, 51_234);
        assertEquals(new InetSocketAddress(mapped, 51_234),
                StunBindingMessage.xorMappedAddress(response, transactionId));
    }

    @Test
    void providerUsesCallerOwnedTransportAndReturnsServerReflexiveCandidate() throws Exception {
        byte[] transactionId = new byte[StunBindingMessage.TRANSACTION_ID_BYTES];
        InetSocketAddress server = new InetSocketAddress("192.0.2.10", 3478);
        InetAddress mapped = InetAddress.getByName("203.0.113.9");
        StunBindingTransport transport = (target, request, cancellation) -> {
            assertEquals(server, target);
            assertEquals(20, request.length);
            return CompletableFuture.completedFuture(
                    successResponse(java.util.Arrays.copyOfRange(request, 8, 20), mapped, 45_678));
        };
        StunCandidateProvider provider = new StunCandidateProvider(server, 10, transport);

        List<Candidate> candidates = provider.gather(() -> false).toCompletableFuture().join();
        assertEquals(List.of(new Candidate(CandidateType.SERVER_REFLEXIVE, mapped, 45_678, 10)), candidates);
        provider.close();
    }

    @Test
    void rejectsMalformedResponsesAndInvalidConfiguration() throws Exception {
        byte[] transactionId = new byte[StunBindingMessage.TRANSACTION_ID_BYTES];
        assertThrows(IllegalArgumentException.class,
                () -> StunBindingMessage.bindingRequest(new byte[3]));
        assertThrows(java.io.IOException.class,
                () -> StunBindingMessage.xorMappedAddress(new byte[20], transactionId));
        assertThrows(IllegalArgumentException.class,
                () -> new StunCandidateProvider(new InetSocketAddress("192.0.2.10", 3478), 0,
                        (target, request, cancellation) -> CompletableFuture.completedFuture(new byte[0])));
    }

    private static byte[] successResponse(byte[] transactionId, InetAddress address, int port) {
        byte[] addressBytes = address.getAddress();
        byte[] value = new byte[4 + addressBytes.length];
        value[1] = (byte) (addressBytes.length == 4 ? 0x01 : 0x02);
        int cookie = 0x2112A442;
        value[2] = (byte) ((port ^ (cookie >>> 16)) >>> 8);
        value[3] = (byte) (port ^ (cookie >>> 16));
        byte[] mask = ByteBuffer.allocate(16).putInt(cookie).put(transactionId).array();
        for (int index = 0; index < addressBytes.length; index++) {
            value[4 + index] = (byte) (addressBytes[index] ^ mask[index]);
        }
        ByteBuffer response = ByteBuffer.allocate(20 + 4 + value.length);
        response.putShort((short) 0x0101).putShort((short) (4 + value.length)).putInt(cookie)
                .put(transactionId).putShort((short) 0x0020).putShort((short) value.length).put(value);
        return response.array();
    }
}
