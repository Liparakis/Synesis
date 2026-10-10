package org.synesis.projectrecord.sync;

import java.util.List;
import java.util.Objects;
import org.synesis.link.session.PeerSession;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.persistence.DecisionStore;

/**
 * Handles project-wide bidirectional reconciliation (PRP1) over one authenticated session.
 */
@SuppressWarnings("ClassCanBeRecord")
public final class ProjectReconciliationSync {

  /**
   * Maximum inventory entries limit (safety bound).
   */
  public static final int MAX_INVENTORY_ENTRIES = 1_000;
  /**
   * Maximum revisions per record (safety bound).
   */
  public static final int MAX_REVISIONS = DecisionStore.MAX_REVISIONS_PER_RECORD;
  /**
   * Maximum session bytes transferred (safety bound).
   */
  public static final int MAX_SESSION_BYTES = 10 * 1024 * 1024;
  /**
   * Entries per chunk.
   */
  public static final int CHUNK_SIZE = 50;
  private final String localNodeId;
  private final ProjectConfig config;
  private final DecisionStore store;

  /**
   * Creates a project reconciliation sync endpoint.
   *
   * @param localNodeId local node identifier
   * @param config      active project configuration
   * @param store       local decision store
   */
  public ProjectReconciliationSync(String localNodeId, ProjectConfig config, DecisionStore store) {
    this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
    this.config = Objects.requireNonNull(config, "config");
    this.store = Objects.requireNonNull(store, "store");
  }

  /**
   * Returns handler for incoming server-side application streams.
   *
   * @return stream handler callback
   */
  public PeerSession.ApplicationStreamHandler handler() {
    return new ReconciliationHost(localNodeId, config, store)::handle;
  }

  /**
   * Executes client-side bidirectional project synchronization.
   *
   * @param session authenticated Link session
   * @return reconciliation result
   */
  public ReconciliationResult syncProject(PeerSession session) {
    Objects.requireNonNull(session, "session");
    return new ReconciliationClient(localNodeId, config, store, session::requestApplication).run();
  }

  /**
   * Reconciled per-record action/outcome.
   */
  public enum RecordStatus {
    /**
     * Revision was duplicated.
     */
    DUPLICATE,
    /**
     * Revision was stale.
     */
    REMOTE_STALE,
    /**
     * Revision had Divergence and was quarantined.
     */
    CONFLICT,
    /**
     * Revision failed verification.
     */
    REJECTED,
    /**
     * Revision was successfully applied.
     */
    APPLIED
  }

  /**
   * Project-level reconciliation result.
   */
  public static final class ReconciliationResult {

    private final boolean success;
    private final int reconciledCount;
    private final int addedLocal;
    private final int addedRemote;
    private final int duplicateCount;
    private final int corruptLocalCount;
    private final int corruptRemoteCount;
    private final List<String> outcomes;
    private final String hint;

    /**
     * Creates a reconciliation result instance.
     *
     * @param success            true if reconciliation fully succeeded without corrupt records
     * @param reconciledCount    total number of records reconciled
     * @param addedLocal         number of records added locally
     * @param addedRemote        number of records uploaded to remote
     * @param duplicateCount     number of duplicate records observed
     * @param corruptLocalCount  number of corrupt records observed locally
     * @param corruptRemoteCount number of corrupt records observed on remote
     * @param outcomes           list of per-record outcome descriptions
     * @param hint               diagnostic hint string
     */
    public ReconciliationResult(boolean success, int reconciledCount, int addedLocal,
        int addedRemote,
        int duplicateCount, int corruptLocalCount, int corruptRemoteCount, List<String> outcomes,
        String hint) {
      this.success = success;
      this.reconciledCount = reconciledCount;
      this.addedLocal = addedLocal;
      this.addedRemote = addedRemote;
      this.duplicateCount = duplicateCount;
      this.corruptLocalCount = corruptLocalCount;
      this.corruptRemoteCount = corruptRemoteCount;
      this.outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
      this.hint = hint == null ? "" : hint;
    }

    /**
     * Returns whether the inventories converged exactly with no corruptions.
     *
     * @return true if successful
     */
    public boolean success() {
      return success;
    }

    /**
     * Returns total records reconciled.
     *
     * @return reconciled count
     */
    public int reconciledCount() {
      return reconciledCount;
    }

    /**
     * Returns local additions count.
     *
     * @return local additions count
     */
    public int addedLocal() {
      return addedLocal;
    }

    /**
     * Returns remote additions count.
     *
     * @return remote additions count
     */
    public int addedRemote() {
      return addedRemote;
    }

    /**
     * Returns duplicate count.
     *
     * @return duplicate count
     */
    public int duplicateCount() {
      return duplicateCount;
    }

    /**
     * Returns corrupt local count.
     *
     * @return corrupt local count
     */
    public int corruptLocalCount() {
      return corruptLocalCount;
    }

    /**
     * Returns corrupt remote count.
     *
     * @return corrupt remote count
     */
    @SuppressWarnings("unused")
    public int corruptRemoteCount() {
      return corruptRemoteCount;
    }

    /**
     * Returns detailed per-record outcomes.
     *
     * @return list of outcomes
     */
    public List<String> outcomes() {
      return outcomes;
    }

    /**
     * Returns diagnostic hint.
     *
     * @return diagnostic hint
     */
    @SuppressWarnings("unused")
    public String hint() {
      return hint;
    }
  }
}
