package org.synesis.cli.command.coordination;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.CoordinationCliSupport;
import org.synesis.cli.daemon.BrowserLauncher;
import org.synesis.cli.daemon.DaemonClient;
import org.synesis.cli.daemon.RuntimeReadyLine;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.ui.StaticResourceHandler;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationReplay;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.MemberReplication;
import org.synesis.coordination.replication.ReplicationJournal;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.ReplayGuard;

import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.lifecycle.codex.ProjectRuntimeHost;
import org.synesis.workspace.transport.control.ControlPlaneEventHub;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler;
import org.synesis.workspace.transport.control.ControlPlaneReadModel;
import org.synesis.workspace.transport.control.LinkRuntimeOwner;
import org.synesis.workspace.transport.control.network.LinkNetworkProjection;

/**
 * Shared composition for the coordination server and the installed browser UI command.
 */
final class CoordinationServerLauncher {

    /** How long after a member's entry the leader's merge pass runs, so a burst of entries is merged together. */
    private static final long MERGE_DELAY_MILLIS = 3_000L;

    private CoordinationServerLauncher() {
    }

    /**
     * Starts one local coordination server.
     *
     * @param runtime     composed CLI runtime
     * @param project     project directory, or {@code null}
     * @param data        coordination data override, or {@code null}
     * @param identity    identity directory override, or {@code null}
     * @param host        loopback host
     * @param port        listener port, with zero selecting an ephemeral port
     * @param duration    optional bounded duration in seconds
     * @param openBrowser whether to open the packaged UI
     * @param browserOpen whether browser opening is enabled
     * @return stable process exit code
     */
    static Integer run(CliRuntime runtime, Path project, Path data, Path identity, String host, int port, int duration, boolean openBrowser, boolean browserOpen) {
        return run(runtime, project, data, identity, host, port, duration, openBrowser, browserOpen, -1L);
    }

    /**
     * Starts one local coordination server with optional installation-daemon ownership.
     *
     * @param runtime     composed CLI runtime
     * @param project     project directory, or {@code null}
     * @param data        coordination data override, or {@code null}
     * @param identity    identity directory override, or {@code null}
     * @param host        loopback host
     * @param port        listener port
     * @param duration    optional bounded duration in seconds
     * @param openBrowser whether the caller requests browser opening
     * @param browserOpen whether browser opening is enabled
     * @param parentPid   owning daemon PID, or a non-positive value for no owner watch
     * @return stable process exit code
     */
    @SuppressWarnings("try") // the replication side is held for the server's lifetime and only closed
    static Integer run(CliRuntime runtime, Path project, Path data, Path identity, String host, int port, int duration, boolean openBrowser, boolean browserOpen, long parentPid) {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var projectRegistry = new KnownProjectRegistry();
            try {
                projectRegistry.observe(location);
            } catch (KnownProjectRegistry.RegistryException discoveryFailure) {
                runtime.terminal().stderr("PROJECT_DISCOVERY_ERROR=" + discoveryFailure.code());
            }
            var projectData = CoordinationCliSupport.data(location, data);
            var legacyLog = location.synesisDirectory().resolve("shared/coordination");
            if (data == null && java.nio.file.Files.isDirectory(legacyLog.resolve("events"))) {
                // Left in place, neither read nor deleted: it holds events no agent wrote, so the operator decides.
                runtime.terminal().stderr("COORDINATION_LEGACY_LOG_IGNORED=" + legacyLog);
            }
            var identityDirectory = CoordinationCliSupport.identity(location, identity);
            var node = CoordinationCliSupport.loadIdentity(identityDirectory);
            // A folder whose replication settings cannot be read must not be served as a leader's.
            var replicationSettings = ReplicationSettings.read(projectData);
            var store = new PredictionEventStore(projectData, location.projectId());
            var service = new CoordinationService(store, node);
            if (!isLoopback(host)) {
                runtime.terminal().stderr("COORDINATION_ERROR=LOOPBACK_ONLY");
                return ExitCodes.LOCAL_CONFIGURATION;
            }
            var eventHub = new ControlPlaneEventHub();
            var controlOnboarding = new Onboarding(identityDirectory.resolve("link"), eventHub::publish,
                    Onboarding.stunServerFromEnvironment());
            // A member's agents reach the leader through this server; the Link session arrives at attach.
            var memberReplication = replicationSettings.filter(ReplicationSettings::isMember).isPresent()
                    ? MemberReplication.open(projectData, location.projectId()) : null;
            // The leader's merge pass follows a member's entry without an agent of the leader's calling in.
            var mergeDriver = replicationSettings.filter(settings -> !settings.isMember()).isPresent()
                    ? MergeDriver.forProject(location.root(), projectData, location.projectId(), node, MERGE_DELAY_MILLIS) : null;
            var leaderReplication = mergeDriver != null
                    ? LeaderReplication.open(projectData, location.projectId(), mergeDriver::onMemberEntry) : null;
            var linkOwner = linkOwner(controlOnboarding, node, eventHub, location.identity(), replicationSettings.orElse(null), memberReplication, leaderReplication, new ReplicationJournal(projectData), projectData);
            var readModel = new ControlPlaneReadModel(location, service, runtime.providerService(), new DoctorService(), new LinkNetworkProjection(linkOwner), projectRegistry);
            var control = new ControlPlaneHttpHandler(readModel, service, linkOwner, eventHub, new ControlPlaneHttpHandler.SelectionGateway() {
                @Override
                public java.util.Map<String, Object> describe(java.util.UUID selectionId) throws java.io.IOException {
                    return DaemonClient.describeSelection(selectionId);
                }

                @Override
                public java.util.Map<String, Object> select(java.util.UUID selectionId, java.util.UUID projectId) throws java.io.IOException {
                    return DaemonClient.selectProject(selectionId, projectId);
                }
            }, projectRegistry);
            // The replication side is listed last so it stops first: it answers on the sessions the link owner holds.
            try (control;
                    linkOwner;
                    var lifecycleHost = new ProjectRuntimeHost(location, node);
                    var server = new CoordinationHttpServer(service, new InetSocketAddress(host, port), lifecycleHost.handler(), control, new StaticResourceHandler());
                    memberReplication;
                    mergeDriver;
                    leaderReplication) {
                server.start();
                if (memberReplication != null) {
                    memberReplication.serve(server);
                }
                Thread parentWatcher = parentPid > 0 ? parentWatcher(parentPid) : null;
                String endpoint = "http://" + hostForUrl(server.address().getHostString()) + ":" + server.address().getPort();
                try {
                    String uiUrl = endpoint + "/#bootstrap=" + URLEncoder.encode(control.bootstrapToken(), StandardCharsets.UTF_8);
                    runtime.terminal().stdout(RuntimeReadyLine.format(endpoint, String.valueOf(location.projectId()), String.valueOf(node.nodeId()), String.valueOf(lifecycleHost.hostInstanceId()),
                            String.valueOf(lifecycleHost.route()), control.bootstrapToken(), control.runtimeCommandToken()));
                    if (openBrowser && browserOpen) {
                        if (!BrowserLauncher.open(URI.create(uiUrl))) {
                            runtime.terminal().stdout("SYNESIS_UI_OPEN_FAILED url=" + uiUrl);
                        } else {
                            runtime.terminal().stdout("SYNESIS_UI_OPENED");
                        }
                    }
                    if (duration > 0) {
                        Thread.sleep(Duration.ofSeconds(duration).toMillis());
                    } else {
                        while (!Thread.currentThread().isInterrupted()) {
                            LockSupport.parkNanos(java.util.concurrent.TimeUnit.SECONDS.toNanos(1L));
                        }
                    }
                } finally {
                    if (parentWatcher != null) {
                        parentWatcher.interrupt();
                    }
                }
            }
            return ExitCodes.OK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COORDINATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    /**
     * The owner of the Link sessions the browser control plane's invite and join establish. On a computer with a
     * replication role those sessions carry the replication of the log (ADR-0098): the role's side answers its
     * frames, and a session whose peer is the leader (for a member) or a member (for the leader) is attached to it.
     * A peer that is neither is still admitted, as before, and gets nothing from the log: the replication side
     * refuses it. The leader's member list is read afresh per request, so the check here only decides who is told
     * of new entries at once; a member added after its session was admitted pulls at its next interval.
     *
     * <p>A session that ends is dialed again by the computer that dialed it, and admitted again by the one that hosted it,
     * without a new invitation, for exactly the nodes the replication side attaches: a member's leader and a leader's
     * current members. Every session start and end is written to the replication journal, with the reason it ended.
     *
     * <p>Those peers are also written to {@code replication.peers} in {@code coordination} as sessions are made, and
     * read back here: a restart of this server dials the hosts it dialed and listens again on the ports it hosted, for
     * the nodes the role still names, with no new invitation. A file that cannot be read restores nothing.
     */
    static LinkRuntimeOwner linkOwner(Onboarding onboarding, NodeIdentity node, ControlPlaneEventHub eventHub, ProjectIdentity identity, ReplicationSettings settings,
            MemberReplication member, LeaderReplication leader, ReplicationJournal journal, Path coordination) {
        LinkRuntimeOwner.PeerMemory memory = peerMemory(coordination, journal);
        LinkRuntimeOwner owner;
        if (member != null) {
            owner = new LinkRuntimeOwner(onboarding, node, eventHub::publish, identity, member.handler(), session -> {
                if (session.remoteNodeId().equals(settings.leaderNodeId())) {
                    member.attach(session);
                }
            }, settings.leaderNodeId()::equals, journal::record, memory);
        } else if (leader != null) {
            owner = new LinkRuntimeOwner(onboarding, node, eventHub::publish, identity, leader.handler(), session -> {
                if (leader.isMember(session.remoteNodeId())) {
                    leader.attach(session);
                }
            }, leader::isMember, journal::record, memory);
        } else {
            return new LinkRuntimeOwner(onboarding, node, null, eventHub::publish, identity);
        }
        ReplicationPeers peers = ReplicationPeers.none();
        try {
            peers = ReplicationPeers.read(coordination);
        } catch (CoordinationException unreadable) {
            journal.record("PEERS_IGNORED code=" + unreadable.code() + "; no session is resumed, a new invitation is needed");
        }
        // The guard is restored even when nothing is resumed: invitations made from now on need it saved too.
        boolean hostingMayResume = restoreReplayGuard(onboarding, coordination, journal, !peers.hosted().isEmpty());
        peers.dialed().values().forEach(owner::resumeDialing);
        if (hostingMayResume) {
            peers.hosted().forEach(owner::resumeHosting);
            return owner;
        }
        try {
            ReplicationPeers.forgetHosted(coordination);
        } catch (java.io.IOException unwritable) {
            journal.record("PEERS_NOT_SAVED cause=" + unwritable.getMessage() + "; the hosting records stay");
        }
        return owner;
    }

    /**
     * Gives the onboarding the replay guard of a retained host as the last run left it, before any endpoint listens,
     * and saves each handshake the guard accepts before the session is handed on. A host that listens again on its old
     * port may resume only with that memory: a missing or unreadable file while a hosting record exists means a
     * recorded handshake could be replayed, so the answer is false and the caller resumes no hosting (fail closed).
     * The guard still starts, empty, for invitations made from now on.
     */
    private static boolean restoreReplayGuard(Onboarding onboarding, Path coordination, ReplicationJournal journal, boolean hostingRecorded) {
        int capacity = Onboarding.retainedReplayEntries();
        java.util.Set<String> restored = java.util.Set.of();
        boolean known;
        try {
            var read = ReplicationReplay.read(coordination, capacity);
            restored = read.orElse(restored);
            known = read.isPresent() || !hostingRecorded;
        } catch (CoordinationException unreadable) {
            known = false;
        }
        onboarding.useRetainedReplayGuard(new ReplayGuard(capacity, restored, fingerprints -> ReplicationReplay.write(coordination, fingerprints)));
        if (!known) {
            journal.record("REPLAY_GUARD_UNREADABLE code=" + ReplicationReplay.INVALID + "; no hosting is resumed, a new invitation is needed");
        }
        return known;
    }

    @FunctionalInterface
    private interface PeersWrite {
        void run() throws java.io.IOException;
    }

    private static LinkRuntimeOwner.PeerMemory peerMemory(Path coordination, ReplicationJournal journal) {
        return new LinkRuntimeOwner.PeerMemory() {
            @Override
            public void dialed(String hostNodeId, String invitationLink) {
                save(() -> ReplicationPeers.rememberDialed(coordination, hostNodeId, invitationLink));
            }

            @Override
            public void hosted(int port, String nodeId) {
                save(() -> ReplicationPeers.rememberHosted(coordination, port, nodeId));
            }

            private void save(PeersWrite write) {
                try {
                    write.run();
                } catch (java.io.IOException unwritable) {
                    journal.record("PEERS_NOT_SAVED cause=" + unwritable.getMessage() + "; a restart will need a new invitation");
                }
            }
        };
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static String hostForUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static Thread parentWatcher(long parentPid) {
        Thread owner = Thread.currentThread();
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                java.util.Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
                if (parent.isEmpty() || !parent.get().isAlive()) {
                    owner.interrupt();
                    return;
                }
                LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250L));
            }
        }, "synesis-project-runtime-parent-watch");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }
}
