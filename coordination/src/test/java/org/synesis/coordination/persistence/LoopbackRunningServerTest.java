package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code synesis status} says the runtime runs only when something listens where the server recorded itself: a file left
 * by a server that stopped is not a running server.
 */
final class LoopbackRunningServerTest {

    @TempDir
    Path folder;

    @Test
    void aServerListeningAtTheRecordedAddressIsRunning() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LoopbackExchange.recordEndpoint(folder, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));

            assertEquals(Optional.of(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/")), new LoopbackExchange(folder).runningServer());
        }
    }

    @Test
    void anAddressNobodyListeningOnIsNotRunning() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        LoopbackExchange.recordEndpoint(folder, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

        assertEquals(Optional.empty(), new LoopbackExchange(folder).runningServer());
    }

    @Test
    void noFileOrAFileThatIsNotLoopbackOrMalformedIsNotRunning() throws Exception {
        assertEquals(Optional.empty(), new LoopbackExchange(folder).runningServer());
        for (String text : new String[] {"203.0.113.9:80\n", "garbage\n", "127.0.0.1:notaport\n", "127.0.0.1:0\n", ""}) {
            Files.writeString(folder.resolve(LoopbackExchange.ENDPOINT_FILE), text);
            assertEquals(Optional.empty(), new LoopbackExchange(folder).runningServer(), text);
        }
    }
}
