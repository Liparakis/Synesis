package org.synesis.workspace.test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeOverLink;

/**
 * The code sides of a leader's and a member's coordination servers joined in process, as their Link session joins
 * them, with each computer's processes asking their own side as they would over the loopback {@code /code} route.
 */
public final class CodeLinkPair implements AutoCloseable {

    /** The leader's code side. */
    public final CodeOverLink leader;
    /** The member's code side. */
    public final CodeOverLink member;
    private final Path leaderCoordination;
    private final Path memberCoordination;
    private final AtomicBoolean connected = new AtomicBoolean(true);

    /**
     * Joins the two computers.
     *
     * @param project            the project
     * @param leaderRepository   the leader's control checkout
     * @param leaderCoordination the leader's coordination folder
     * @param leaderNode         the leader's node
     * @param memberRepository   the member's control checkout
     * @param memberCoordination the member's coordination folder
     * @param memberNode         the member's node
     * @throws IOException when a coordination folder cannot be resolved
     */
    public CodeLinkPair(UUID project, Path leaderRepository, Path leaderCoordination, String leaderNode,
            Path memberRepository, Path memberCoordination, String memberNode) throws IOException {
        AtomicReference<CodeOverLink> leaderSide = new AtomicReference<>();
        AtomicReference<CodeOverLink> memberSide = new AtomicReference<>();
        this.leader = new CodeOverLink(leaderRepository, leaderCoordination, project, peers(false, memberNode, memberSide));
        this.member = new CodeOverLink(memberRepository, memberCoordination, project, peers(true, leaderNode, leaderSide));
        leaderSide.set(leader);
        memberSide.set(member);
        this.leaderCoordination = leaderCoordination;
        this.memberCoordination = memberCoordination;
        CodeExchange.register(leaderCoordination, leader::fetch);
        CodeExchange.register(memberCoordination, member::fetch);
    }

    /** Cuts the session: neither computer has a live peer until {@link #reconnect}. */
    public void disconnect() {
        connected.set(false);
    }

    /** Restores the session. */
    public void reconnect() {
        connected.set(true);
    }

    private CodeOverLink.Peers peers(boolean member, String node, AtomicReference<CodeOverLink> other) {
        return new CodeOverLink.Peers() {
            @Override
            public Map<String, ReplicationExchange> live() {
                return connected.get() ? Map.of(node, request -> answer(other.get(), request)) : Map.of();
            }

            @Override
            public boolean member() {
                return member;
            }
        };
    }

    private byte[] answer(CodeOverLink side, byte[] request) throws IOException {
        if (!connected.get()) {
            throw new java.net.ConnectException("disconnected");
        }
        try {
            return side.serve(request).toCompletableFuture().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        } catch (ExecutionException | TimeoutException lost) {
            throw new IOException(lost);
        }
    }

    @Override
    public void close() throws IOException {
        CodeExchange.register(leaderCoordination, null);
        CodeExchange.register(memberCoordination, null);
        leader.close();
        member.close();
    }
}
