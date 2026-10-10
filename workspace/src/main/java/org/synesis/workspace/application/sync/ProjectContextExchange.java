package org.synesis.workspace.application.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.synesis.link.session.PeerSession;
import org.synesis.workspace.application.project.ProjectIdentity;

/**
 * Bounded project-context exchange performed after Link authentication.
 *
 * <p>This is a higher-level application-stream contract. Core Link remains
 * unaware of project identity; project consumers must complete this exchange
 * before they send or handle project mutations.</p>
 *
 * @since 1.0
 */
public final class ProjectContextExchange {

    /**
     * Maximum encoded context frame size.
     */
    public static final int MAX_BYTES = 64;
    private static final int MAGIC = 0x53505831;
    private static final int VERSION = 1;
    private static final int KIND_REQUEST = 1;
    private static final int KIND_ACKNOWLEDGE = 2;
    private static final int KIND_REJECT = 3;
    private static final long TIMEOUT_SECONDS = 5;

    private ProjectContextExchange() {
    }

    /**
     * Returns whether a payload begins with this exchange's magic prefix.
     *
     * @param payload candidate application payload
     * @return whether the payload is addressed to this exchange
     */
    static boolean looksLike(byte[] payload) {
        return payload != null && payload.length >= Integer.BYTES && ((payload[0] & 0xff) << 24 | (payload[1] & 0xff) << 16 | (payload[2] & 0xff) << 8 | (payload[3] & 0xff)) == MAGIC;
    }

    /**
     * Creates the client request frame.
     *
     * @param identity local canonical project identity
     * @return bounded request bytes
     */
    public static byte[] request(ProjectIdentity identity) {
        return encode(KIND_REQUEST, Objects.requireNonNull(identity, "project identity"));
    }

    /**
     * Creates an acknowledgement frame for a matching request.
     *
     * @param identity local canonical project identity
     * @return bounded acknowledgement bytes
     */
    static byte[] acknowledge(ProjectIdentity identity) {
        return encode(KIND_ACKNOWLEDGE, Objects.requireNonNull(identity, "project identity"));
    }

    /**
     * Creates a rejection frame.
     *
     * @param identity local canonical project identity
     * @return bounded rejection bytes
     */
    public static byte[] reject(ProjectIdentity identity) {
        return encode(KIND_REJECT, Objects.requireNonNull(identity, "project identity"));
    }

    /**
     * Performs the authenticated client-side exchange.
     *
     * @param session       authenticated, control-ready Link session
     * @param localIdentity local canonical project identity
     * @throws ProjectContextException if the remote context is malformed or mismatched
     */
    public static void require(PeerSession session, ProjectIdentity localIdentity) throws ProjectContextException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(localIdentity, "local identity");
        try {
            byte[] response = session.requestApplication(request(localIdentity)).toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Context context = decode(response);
            if (context.kind() != Kind.ACKNOWLEDGE || !localIdentity.matches(context.identity())) {
                throw new ProjectContextException("PROJECT_MISMATCH", "authenticated peer is not bound to the same project identity");
            }
        } catch (ProjectContextException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectContextException(failure);
        }
    }

    /**
     * Wraps a project application handler with a pre-mutation context responder.
     *
     * @param localIdentity local canonical project identity
     * @param delegate      handler for already admitted project payloads
     * @return bounded handler that consumes context requests first
     */
    public static PeerSession.ApplicationStreamHandler withResponder(ProjectIdentity localIdentity, PeerSession.ApplicationStreamHandler delegate) {
        Objects.requireNonNull(localIdentity, "local identity");
        Objects.requireNonNull(delegate, "delegate");
        var admittedRemotes = ConcurrentHashMap.<String>newKeySet();
        return (remote, payload) -> {
            Objects.requireNonNull(remote, "remote node ID");
            if (!looksLike(payload)) {
                if (!admittedRemotes.contains(remote)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("project context is required before application traffic"));
                }
                return delegate.handle(remote, payload);
            }
            try {
                Context context = decode(payload);
                if (context.kind() != Kind.REQUEST || !localIdentity.matches(context.identity())) {
                    admittedRemotes.remove(remote);
                    return CompletableFuture.completedFuture(reject(localIdentity));
                }
                admittedRemotes.add(remote);
                return CompletableFuture.completedFuture(acknowledge(localIdentity));
            } catch (IOException malformed) {
                admittedRemotes.remove(remote);
                return CompletableFuture.completedFuture(reject(localIdentity));
            }
        };
    }

    private static byte[] encode(int kind, ProjectIdentity identity) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_BYTES);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(kind);
                output.writeLong(identity.projectId().getMostSignificantBits());
                output.writeLong(identity.projectId().getLeastSignificantBits());
                output.writeLong(identity.createdAt().getEpochSecond());
                output.writeInt(identity.createdAt().getNano());
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTES) {
                throw new IllegalStateException("project context exceeds size bound");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes and validates one bounded context frame.
     *
     * @param payload encoded context frame
     * @return decoded frame
     * @throws IOException if framing, version, identity, or bounds are invalid
     */
    public static Context decode(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > MAX_BYTES) {
            throw new IOException("project context exceeds size bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported project context");
            }
            int kind = input.readUnsignedByte();
            if (kind != KIND_REQUEST && kind != KIND_ACKNOWLEDGE && kind != KIND_REJECT) {
                throw new IOException("invalid project context kind");
            }
            UUID projectId = new UUID(input.readLong(), input.readLong());
            Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            if (input.available() != 0) {
                throw new IOException("trailing project context bytes");
            }
            return new Context(Kind.from(kind), new ProjectIdentity(projectId, createdAt));
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new IOException("malformed project context", failure);
        }
    }

    /**
     * Context frame kind.
     */
    public enum Kind {
        /**
         * Client request.
         */
        REQUEST,
        /**
         * Matching project acknowledgement.
         */
        ACKNOWLEDGE,
        /**
         * Project context rejected.
         */
        REJECT;

        private static Kind from(int value) throws IOException {
            return switch (value) {
                case 1 -> Kind.REQUEST;
                case 2 -> Kind.ACKNOWLEDGE;
                case 3 -> Kind.REJECT;
                default -> throw new IOException("invalid project context kind");
            };
        }
    }

    /**
     * Decoded project context frame.
     *
     * @param kind     frame kind
     * @param identity identity carried by the frame
     */
    public record Context(Kind kind, ProjectIdentity identity) {

        /**
         * Validates decoded context values.
         */
        public Context {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
        }
    }

    /**
     * Stable project-context exchange failure.
     */
    public static final class ProjectContextException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;
        /**
         * Stable diagnostic code for this context failure.
         */
        private final String code;

        /**
         * Creates a context failure.
         *
         * @param code    stable failure code
         * @param message safe diagnostic
         */
        public ProjectContextException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        private ProjectContextException(Throwable cause) {
            super("project context exchange failed", cause);
            this.code = "PROJECT_CONTEXT_FAILED";
        }

        /**
         * Returns the stable failure code.
         *
         * @return failure code
         */
        public String code() {
            return code;
        }
    }
}
