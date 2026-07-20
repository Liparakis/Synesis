package org.synesis.projectrecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Bounded on-demand search over one profile's validated decision heads. */
public final class DecisionSearch {
    /** Maximum UTF-8 query text size. */
    public static final int MAX_QUERY_BYTES = 256;
    /** Maximum whitespace-separated text terms. */
    public static final int MAX_TERMS = 4;
    /** Maximum returned rows. */
    public static final int MAX_RESULTS = 64;
    /** Maximum decision heads scanned by one query. */
    public static final int MAX_RECORD_SCAN = 128;

    /** Safe search failure classification. */
    public enum ErrorCode {
        /** Local heads or revision chains are invalid. */ LOCAL_STATE_INVALID,
        /** The bounded head scan could not complete. */ SCAN_LIMIT
    }

    /** Immutable bounded search query. */
    public static final class Query {
        private final String text;
        private final List<String> terms;
        private final UUID recordId;
        private final DecisionStatus status;
        private final String ownerNodeId;
        private final int limit;

        /**
         * Creates one bounded query.
         *
         * @param text optional title/rationale text; terms are ANDed
         * @param recordId optional exact record identity
         * @param status optional exact decision status
         * @param ownerNodeId optional exact owner/author node ID
         * @param limit maximum result rows
         */
        public Query(String text, UUID recordId, DecisionStatus status, String ownerNodeId, int limit) {
            this.text = text == null ? "" : text.trim();
            if (this.text.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES
                    || this.text.indexOf('\u0000') >= 0) throw new IllegalArgumentException("query text exceeds bound");
            String[] split = this.text.isEmpty() ? new String[0] : this.text.split("\\s+");
            if (split.length > MAX_TERMS) throw new IllegalArgumentException("too many query terms");
            this.terms = List.of(split).stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
            this.recordId = recordId;
            this.status = status;
            if (ownerNodeId != null && !ownerNodeId.matches("sl1-[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid owner filter");
            }
            this.ownerNodeId = ownerNodeId;
            if (limit <= 0 || limit > MAX_RESULTS) throw new IllegalArgumentException("result limit exceeds bound");
            this.limit = limit;
        }

        /** Returns the original bounded query text.
         * @return query text
         */
        public String text() { return text; }
        /** Returns the exact record filter, or null.
         * @return record filter
         */
        public UUID recordId() { return recordId; }
        /** Returns the exact status filter, or null.
         * @return status filter
         */
        public DecisionStatus status() { return status; }
        /** Returns the exact owner filter, or null.
         * @return owner filter
         */
        public String ownerNodeId() { return ownerNodeId; }
        /** Returns the maximum result count.
         * @return result limit
         */
        public int limit() { return limit; }
    }

    /** Immutable safe projection of one verified decision head. */
    public static final class Result {
        private final DecisionRecord record;

        private Result(DecisionRecord record) { this.record = record; }

        /** Returns the stable record identity.
         * @return record identity
         */
        public UUID recordId() { return record.recordId(); }
        /** Returns the current revision.
         * @return revision
         */
        public long revision() { return record.revision(); }
        /** Returns the current canonical digest.
         * @return digest text
         */
        public String digestHex() { return record.digestHex(); }
        /** Returns the verified owner/author node ID.
         * @return node ID
         */
        public String ownerNodeId() { return record.ownerNodeId(); }
        /** Returns the decision status.
         * @return status
         */
        public DecisionStatus status() { return record.status(); }
        /** Returns the bounded title.
         * @return title
         */
        public String title() { return record.title(); }
        /** Returns the bounded rationale.
         * @return rationale
         */
        public String rationale() { return record.rationale(); }
    }

    /** Immutable search response with either rows or a safe failure. */
    public static final class SearchResult {
        private final List<Result> results;
        private final ErrorCode errorCode;

        private SearchResult(List<Result> results, ErrorCode errorCode) {
            this.results = List.copyOf(results);
            this.errorCode = errorCode;
        }

        /** Returns immutable matching rows.
         * @return matching rows
         */
        public List<Result> results() { return results; }
        /** Returns the failure code, or null for a successful query.
         * @return failure code
         */
        public ErrorCode errorCode() { return errorCode; }
        /** Returns whether the query completed without a local-state error.
         * @return true when successful
         */
        public boolean isSuccessful() { return errorCode == null; }

        /**
         * Renders a stable safe projection without paths or secret material.
         *
         * @return deterministic UTF-8-friendly text
         */
        public String render() {
            StringBuilder output = new StringBuilder();
            if (errorCode != null) output.append("ERROR=").append(errorCode).append('\n');
            output.append("RESULTS=").append(results.size()).append('\n');
            for (Result result : results) {
                output.append("RECORD_ID=").append(result.recordId()).append('\n');
                output.append("REVISION=").append(result.revision()).append('\n');
                output.append("DIGEST=").append(result.digestHex()).append('\n');
                output.append("OWNER_NODE_ID=").append(result.ownerNodeId()).append('\n');
                output.append("STATUS=").append(result.status()).append('\n');
                output.append("TITLE=").append(escape(result.title())).append('\n');
                output.append("RATIONALE=").append(escape(result.rationale())).append('\n');
            }
            return output.toString();
        }
    }

    private final DecisionStore store;

    /**
     * Creates a read-only view over one local decision store.
     *
     * @param store profile-local decision store
     */
    public DecisionSearch(DecisionStore store) { this.store = Objects.requireNonNull(store, "decision store"); }

    /**
     * Searches validated current heads on demand.
     *
     * @param query bounded query and result limit
     * @return matching rows or a safe local-state failure
     */
    public SearchResult search(Query query) {
        Objects.requireNonNull(query, "query");
        try {
            List<DecisionRecord> heads = store.verifiedHeads(MAX_RECORD_SCAN);
            List<Result> matches = new ArrayList<>();
            for (DecisionRecord record : heads) {
                if (!matches(query, record)) continue;
                matches.add(new Result(record));
            }
            matches.sort(Comparator.<Result, String>comparing(result -> result.recordId().toString())
                    .thenComparingLong(Result::revision).thenComparing(Result::digestHex));
            return new SearchResult(matches.subList(0, Math.min(query.limit(), matches.size())), null);
        } catch (java.io.IOException | IllegalArgumentException failure) {
            ErrorCode code = failure.getMessage() != null && failure.getMessage().contains("bound")
                    ? ErrorCode.SCAN_LIMIT : ErrorCode.LOCAL_STATE_INVALID;
            return new SearchResult(List.of(), code);
        }
    }

    private static boolean matches(Query query, DecisionRecord record) {
        if (query.recordId != null && !query.recordId.equals(record.recordId())) return false;
        if (query.status != null && query.status != record.status()) return false;
        if (query.ownerNodeId != null && !query.ownerNodeId.equals(record.ownerNodeId())) return false;
        String searchable = (record.title() + "\n" + record.rationale()).toLowerCase(Locale.ROOT);
        return query.terms.stream().allMatch(searchable::contains);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
