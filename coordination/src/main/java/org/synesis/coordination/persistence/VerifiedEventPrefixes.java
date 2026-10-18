package org.synesis.coordination.persistence;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;

/**
 * Remembers, for this process, the longest prefix of each project's event log whose chain and signatures
 * {@link PredictionEventStore} has already verified, so that opening a store or appending to one only reads
 * and verifies the files written since.
 *
 * <p>The log is append-only and its files are immutable, so a verified prefix stays valid as long as the
 * directory still holds those files; the store re-checks that on every use and drops the entry when it does
 * not. Files inside the prefix are not read again within the process: a process that can rewrite one of them
 * runs as the same user on the same disk and could equally write a new, correctly signed log. Every file
 * beyond the prefix is read, chained and verified exactly as before. Memory is bounded by the number of logs
 * kept, and the events are the immutable objects the stores hold anyway.</p>
 */
final class VerifiedEventPrefixes {

    private static final int MAX_LOGS = 64;

    private static final Map<Key, List<PredictionEvent>> PREFIXES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, List<PredictionEvent>> eldest) {
            return size() > MAX_LOGS;
        }
    };

    private VerifiedEventPrefixes() {
    }

    /**
     * Returns the verified prefix remembered for a log.
     *
     * @param eventsDirectory real path of the events directory
     * @param projectId       project the log belongs to
     * @return immutable events in sequence order; empty when nothing is remembered
     */
    static synchronized List<PredictionEvent> recall(Path eventsDirectory, UUID projectId) {
        return PREFIXES.getOrDefault(new Key(eventsDirectory, projectId), List.of());
    }

    /**
     * Remembers a longer verified prefix; a shorter one, from a scan that raced a longer one, is ignored.
     *
     * @param eventsDirectory real path of the events directory
     * @param projectId       project the log belongs to
     * @param verified        events in sequence order, each chained to and verified after its predecessor
     */
    static synchronized void remember(Path eventsDirectory, UUID projectId, List<PredictionEvent> verified) {
        Key key = new Key(eventsDirectory, projectId);
        if (verified.size() > PREFIXES.getOrDefault(key, List.of()).size()) {
            PREFIXES.put(key, List.copyOf(verified));
        }
    }

    /**
     * Forgets a log whose directory no longer matches what was verified.
     *
     * @param eventsDirectory real path of the events directory
     * @param projectId       project the log belongs to
     */
    static synchronized void forget(Path eventsDirectory, UUID projectId) {
        PREFIXES.remove(new Key(eventsDirectory, projectId));
    }

    private record Key(Path eventsDirectory, UUID projectId) {

    }
}
