package org.synesis.workspace.application;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.transport.Onboarding;
import org.synesis.link.transport.OnboardingFailure;
import org.synesis.link.transport.OnboardingFailureCode;
import org.synesis.link.transport.OnboardingEventType;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectReconciliationSync;
import org.synesis.projectrecord.ProjectRecordSync;

/**
 * Owns project synchronization orchestration for the unified CLI.
 */
public final class SyncApplicationService {
    private static final Path PROJECT_CONFIG = Path.of("project.conf");

    /**
     * Creates the service.
     */
    public SyncApplicationService() {
    }

    /**
     * Hosts one bounded synchronization session.
     *
     * @param profile        local profile directory
     * @param project        optional expected project identifier
     * @param record         optional record identifier
     * @param invitationSink receives the generated invitation
     * @return structured result
     */
    public SyncResult host(Path profile, String project, String record, Consumer<String> invitationSink) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(invitationSink, "invitationSink");
        try {
            ProjectConfig config = loadConfig(profile);
            UUID projectId = parseUuid(project, "PROJECT_INVALID");
            UUID recordId = parseUuid(record, "RECORD_INVALID");
            if (projectId != null && !projectId.equals(config.projectId())) throw failure("PROJECT_MISMATCH");
            if (config.peerNodeIds().size() != 1) throw failure("PROJECT_INVALID");
            if (recordId != null) validateRecord(profile, config.projectId(), recordId);

            String hostNode = identity(profile).nodeId();
            DecisionStore store = new DecisionStore(profile.resolve("records"), config.projectId());
            PeerSession.ApplicationStreamHandler records = new ProjectRecordSync(config, store).handler();
            PeerSession.ApplicationStreamHandler reconciliation = new ProjectReconciliationSync(hostNode, config, store).handler();
            PeerSession.ApplicationStreamHandler handler = (remote, bytes) -> isReconciliation(bytes)
                    ? reconciliation.handle(remote, bytes) : records.handle(remote, bytes);
            Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> {
                if (event.type() == OnboardingEventType.SHARE_LINK) {
                    invitationSink.accept(composeInvitation(event.value(), config.projectId(), recordId, hostNode));
                }
            });
            try {
                onboarding.host(config.peerNodeIds().iterator().next(), handler, ignored -> {
                });
            } catch (OnboardingFailure failure) {
                throw failure(mapOnboarding(failure.code()));
            }
            return success();
        } catch (SyncFailure failure) {
            return failed(failure.code);
        } catch (Exception failure) {
            return failed("SYNC_FAILED");
        }
    }

    /**
     * Joins one synchronization session.
     *
     * @param profile      local profile directory
     * @param project      optional project identifier; invitation query is used otherwise
     * @param record       optional record identifier; invitation query is used otherwise
     * @param expectedHost expected host node identifier
     * @param invitation   signed invitation link
     * @return structured result
     */
    public SyncResult join(Path profile, String project, String record, String expectedHost, String invitation) {
        Objects.requireNonNull(profile, "profile");
        try {
            URI uri = URI.create(invitation);
            Map<String, String> query = query(uri.getQuery());
            String clean = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment()).toString();
            var decoded = org.synesis.link.protocol.SessionInvitation.fromShareLink(clean);
            var host = org.synesis.link.candidate.CandidateDescriptor.decode(decoded.descriptorEncoded()).nodeId();
            if (query.containsKey("host") && !host.equals(query.get("host"))) throw failure("AUTH_FAILED");
            if (expectedHost != null && !expectedHost.equals(host)) throw failure("AUTH_FAILED");
            UUID projectId = parseUuid(project != null ? project : query.get("project"), "PROJECT_INVALID");
            if (projectId == null) throw failure("USAGE");
            UUID recordId = parseUuid(record != null ? record : query.get("record"), "RECORD_INVALID");
            ProjectConfig config = existingOrCreate(profile, projectId, host, expectedHost != null);
            AtomicReference<SyncFailure> callbackFailure = new AtomicReference<>();
            Map<String, String> values = new LinkedHashMap<>();
            Onboarding onboarding = new Onboarding(profile.resolve("link"), ignored -> {
            });
            try {
                onboarding.join(clean, null, session -> {
                    try {
                        if (!session.isUsable() || !host.equals(session.remoteNodeId())) throw failure("AUTH_FAILED");
                        values.put("AUTHENTICATED_REMOTE", session.remoteNodeId());
                        DecisionStore store = new DecisionStore(profile.resolve("records"), projectId);
                        String local = identity(profile).nodeId();
                        if (recordId != null) {
                            var outcome = new ProjectRecordSync(config, store).sync(session, recordId);
                            if (outcome.code() != ProjectRecordSync.Code.APPLIED && outcome.code() != ProjectRecordSync.Code.DUPLICATE) {
                                throw failure(outcome.code().name());
                            }
                            values.put("PROJECT_ID", projectId.toString());
                            values.put("RECORD_ID", recordId.toString());
                            values.put("SYNC_RESULT", outcome.code().name());
                        } else {
                            var result = new ProjectReconciliationSync(local, config, store).syncProject(session);
                            values.put("PROJECT_ID", projectId.toString());
                            values.put("RECONCILED_COUNT", Integer.toString(result.reconciledCount()));
                            values.put("ADDED_LOCAL", Integer.toString(result.addedLocal()));
                            values.put("ADDED_REMOTE", Integer.toString(result.addedRemote()));
                            values.put("DUPLICATE_COUNT", Integer.toString(result.duplicateCount()));
                            values.put("SYNC_RESULT", result.success() ? "SUCCESS" : "PARTIAL_SUCCESS");
                            if (!result.success()) throw failure("RECONCILIATION_FAILED");
                        }
                    } catch (SyncFailure failure) {
                        callbackFailure.set(failure);
                        close(session);
                        throw failure;
                    } catch (Exception failure) {
                        SyncFailure safe = failure("SYNC_FAILED");
                        callbackFailure.set(safe);
                        close(session);
                        throw safe;
                    }
                });
            } catch (OnboardingFailure failure) {
                SyncFailure callback = callbackFailure.get();
                if (callback != null) throw callback;
                throw failure(mapOnboarding(failure.code()));
            }
            return new SyncResult(0, values);
        } catch (SyncFailure failure) {
            return failed(failure.code);
        } catch (Exception failure) {
            return failed("SYNC_FAILED");
        }
    }

    private ProjectConfig existingOrCreate(Path profile, UUID project, String host, boolean confirmed) throws Exception {
        Path path = profile.resolve(PROJECT_CONFIG);
        if (Files.exists(path)) {
            ProjectConfig config = ProjectConfig.load(path);
            if (!project.equals(config.projectId()) || !config.peerNodeIds().equals(Set.of(host)))
                throw failure("PROJECT_MISMATCH");
            return config;
        }
        if (!confirmed) throw failure("PROJECT_NOT_CONFIGURED");
        ProjectConfig config = new ProjectConfig(project, Set.of(host));
        config.save(path);
        return config;
    }

    private static ProjectConfig loadConfig(Path profile) throws Exception {
        try {
            return ProjectConfig.load(profile.resolve(PROJECT_CONFIG));
        } catch (Exception failure) {
            throw failure("PROJECT_INVALID");
        }
    }

    private static NodeIdentity identity(Path profile) throws Exception {
        try {
            return new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
        } catch (Exception failure) {
            throw failure("IDENTITY_FAILED");
        }
    }

    private static void validateRecord(Path profile, UUID project, UUID record) throws Exception {
        DecisionStore store = new DecisionStore(profile.resolve("records"), project);
        if (store.head(record).isEmpty()) throw failure("RECORD_NOT_FOUND");
    }

    private static boolean isReconciliation(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x52 && bytes[2] == 0x50 && bytes[3] == 0x31;
    }

    private static String composeInvitation(String link, UUID project, UUID record, String host) {
        return link + "?project=" + project + (record == null ? "" : "&record=" + record) + "&host=" + host;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&", -1)) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) result.put(pair[0], pair[1]);
        }
        return result;
    }

    private static UUID parseUuid(String value, String code) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw failure(code);
        }
    }

    private static void close(PeerSession session) {
        try {
            session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
        } catch (Exception ignored) {
        }
    }

    private static String mapOnboarding(OnboardingFailureCode code) {
        return switch (code) {
            case INVITE_INVALID -> "INVITE_INVALID";
            case HOST_IDENTITY_MISMATCH -> "AUTH_FAILED";
            case IDENTITY_FAILED -> "IDENTITY_FAILED";
            case HOST_TIMEOUT, NO_USABLE_CANDIDATE, CONNECTION_FAILED, INTERNAL -> "TRANSPORT_FAILED";
        };
    }

    private static SyncFailure failure(String code) {
        return new SyncFailure(code);
    }

    private static SyncResult success() {
        return new SyncResult(0, Map.of());
    }

    private static SyncResult failed(String code) {
        return new SyncResult(10, Map.of("ERROR", code));
    }

    private static final class SyncFailure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final String code;

        private SyncFailure(String code) {
            this.code = code;
        }
    }

    /**
     * Structured synchronization result.
     *
     * @param exitCode process-compatible status
     * @param values machine-readable fields
     */
    public record SyncResult(int exitCode, Map<String, String> values) {
        /**
         * Validates the result.
         */
        public SyncResult {
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
        }
    }
}
