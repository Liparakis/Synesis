package org.synesis.coordination.persistence;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * A {@link LeaderChannel} that speaks the replication protocol over a {@link ReplicationExchange}.
 *
 * <p>It is the one place that turns a transport failure into an outcome. A request that did not leave is
 * {@link ProposalOutcome.Unreachable}; one that may have arrived and got no usable answer is
 * {@link ProposalOutcome.NoAnswer}, which the log answers by sending the same bytes again and then reporting that
 * it does not know.</p>
 */
public final class ExchangeLeaderChannel implements LeaderChannel {

    private final UUID project;
    private final ReplicationExchange exchange;

    /**
     * Binds the channel to one project and one transport.
     *
     * @param project  the project every request names
     * @param exchange the transport
     */
    public ExchangeLeaderChannel(UUID project, ReplicationExchange exchange) {
        this.project = CoordinationNullException.requireNonNull(project, "project");
        this.exchange = CoordinationNullException.requireNonNull(exchange, "exchange");
    }

    @Override
    public ProposalOutcome propose(byte[] encodedEvent) throws IOException {
        ReplicationMessage answer = ask(new ReplicationMessage.Propose(project, encodedEvent));
        return answer instanceof ReplicationMessage.Outcome outcome ? outcome.outcome() : new ProposalOutcome.NoAnswer();
    }

    @Override
    public List<byte[]> pull(long after, int maxEntries, int maxBytes) throws IOException {
        ReplicationMessage answer = ask(new ReplicationMessage.Pull(project, after, maxEntries, maxBytes));
        if (answer instanceof ReplicationMessage.Entries entries) {
            return entries.entries();
        }
        if (answer instanceof ReplicationMessage.Outcome outcome
                && outcome.outcome() instanceof ProposalOutcome.Refused refused) {
            throw new CoordinationException(refused.code());
        }
        throw new CoordinationException("LEADER_UNREACHABLE");
    }

    private ReplicationMessage ask(ReplicationMessage request) throws IOException {
        byte[] reply;
        try {
            reply = exchange.exchange(ReplicationCodec.encode(request));
        } catch (ConnectException notSent) {
            return new ReplicationMessage.Outcome(new ProposalOutcome.Unreachable());
        } catch (IOException maybeSent) {
            return new ReplicationMessage.Outcome(new ProposalOutcome.NoAnswer());
        }
        try {
            return ReplicationCodec.decode(reply);
        } catch (CoordinationException malformed) {
            return new ReplicationMessage.Outcome(new ProposalOutcome.NoAnswer());
        }
    }
}
