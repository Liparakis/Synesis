package org.synesis.projectrecord.sync;

import java.io.IOException;
import java.util.List;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.persistence.DecisionStore;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.InventoryEntry;

/**
 * Verified head inventory of a store, sliced into PRP1 chunks. Each side decides for itself how
 * an unreadable snapshot adds to the corrupt count it reports.
 *
 * @param heads      verified heads, empty when the store could not be read
 * @param entries    inventory entries of {@code heads}
 * @param unreadable whether reading the verified heads failed
 */
record InventorySnapshot(List<DecisionRecord> heads, List<InventoryEntry> entries,
                         boolean unreadable) {

  static InventorySnapshot take(DecisionStore store) {
    try {
      return of(store.verifiedHeads(ProjectReconciliationSync.MAX_INVENTORY_ENTRIES), false);
    } catch (IOException e) {
      return of(List.of(), true);
    }
  }

  private static InventorySnapshot of(List<DecisionRecord> heads, boolean unreadable) {
    return new InventorySnapshot(heads, heads.stream()
        .map(h -> new InventoryEntry(h.recordId(), h.revision(), h.digest()))
        .toList(), unreadable);
  }

  /**
   * Returns the number of chunks, at least one so an empty inventory is still announced.
   */
  int chunkCount() {
    return Math.max(1, (entries.size() + ProjectReconciliationSync.CHUNK_SIZE - 1)
        / ProjectReconciliationSync.CHUNK_SIZE);
  }

  boolean hasChunk(int index) {
    return index >= 0 && index < chunkCount();
  }

  /**
   * Returns the entries of chunk {@code index}, which must satisfy {@link #hasChunk}.
   */
  List<InventoryEntry> chunk(int index) {
    int from = index * ProjectReconciliationSync.CHUNK_SIZE;
    return entries.subList(from,
        Math.min(entries.size(), from + ProjectReconciliationSync.CHUNK_SIZE));
  }
}
