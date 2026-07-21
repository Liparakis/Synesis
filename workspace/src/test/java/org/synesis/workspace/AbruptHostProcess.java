package org.synesis.workspace;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.synesis.link.session.PeerSession;
import org.synesis.link.transport.Onboarding;

/** Test-only isolated peer that closes an application request before a result. */
public final class AbruptHostProcess {
    private AbruptHostProcess() { }

    /** Runs one bounded Link host with a failed application response.
     * @param arguments profile path and expected peer node ID
     * @throws Exception when the bounded host operation fails
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("profile required");
        PeerSession.ApplicationStreamHandler handler = (remote, payload) ->
                CompletableFuture.failedFuture(new IllegalStateException("test close"));
        new Onboarding(Path.of(arguments[0]).resolve("link"), event -> {
            if (event.type() == org.synesis.link.transport.OnboardingEventType.SHARE_LINK) {
                System.out.println("INVITATION=" + event.value());
            }
        }).host(null, handler, session -> { });
    }
}
