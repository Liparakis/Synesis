package org.synesis.coordination.domain.trash;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.trash.TrashEntries.TrashEmptied;
import org.synesis.coordination.domain.trash.TrashEntries.TrashRestored;
import org.synesis.coordination.domain.trash.TrashEntries.WorkTrashed;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * The trash as the log says (ADR-0098 step 7, designer items 12 to 16): every piece of work trashed, whether a person
 * brought it back, and whether a person emptied it. Nothing in it is ever forgotten; emptying deletes the commit's ref,
 * not the record.
 *
 * <p>Work is trashed once per lane and epoch. Restoring and emptying name trashed work; an arriving entry that names
 * work whose entry has not arrived waits for it. Emptied work cannot be restored, and is emptied once; an arriving
 * second empty, from a computer that had not seen the first, changes nothing.</p>
 */
public final class TrashProjection {

    private final Map<Key, Item> items = new LinkedHashMap<>();

    /**
     * One piece of trashed work.
     *
     * @param trashed      the entry that trashed it
     * @param writerNodeId the computer that trashed it, which holds the commit
     * @param restoredInto the work groups people brought it back in, oldest first
     * @param emptied      whether a person emptied it
     */
    public record Item(WorkTrashed trashed, String writerNodeId, List<UUID> restoredInto, boolean emptied) {

        /** Copies the list. */
        public Item {
            restoredInto = List.copyOf(restoredInto);
        }
    }

    private record Key(UUID laneId, long epoch) {
    }

    /**
     * Refuses an entry this projection cannot take: on this computer's own append, a second trashing of the same
     * work, or a restore or empty of work that is not in the trash or was emptied. An arriving entry that names work
     * not trashed yet waits.
     *
     * @param event the entry
     * @param mode  how it is applied
     * @throws IOException with the refusal code
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        if (mode == ApplyMode.REPLAY) {
            return;
        }
        switch (event.type()) {
            case WORK_TRASHED -> {
                WorkTrashed trashed = WorkTrashed.decode(event.payload());
                if (mode.checking() && items.containsKey(new Key(trashed.laneId(), trashed.epoch()))) {
                    throw new CoordinationException("WORK_ALREADY_TRASHED");
                }
            }
            case TRASH_RESTORED -> {
                TrashRestored restored = TrashRestored.decode(event.payload());
                Item item = named(restored.laneId(), restored.epoch(), mode);
                if (item.emptied() && mode.checking()) {
                    throw new CoordinationException("TRASH_EMPTIED");
                }
            }
            case TRASH_EMPTIED -> {
                TrashEmptied emptied = TrashEmptied.decode(event.payload());
                Item item = named(emptied.laneId(), emptied.epoch(), mode);
                if (item.emptied() && mode.checking()) {
                    throw new CoordinationException("TRASH_ALREADY_EMPTIED");
                }
            }
            default -> {
            }
        }
    }

    /**
     * Applies a trash entry; other entries change nothing.
     *
     * @param event the entry
     * @throws IOException when its payload is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        switch (event.type()) {
            case WORK_TRASHED -> {
                WorkTrashed trashed = WorkTrashed.decode(event.payload());
                items.putIfAbsent(new Key(trashed.laneId(), trashed.epoch()), new Item(trashed, event.actorNodeId(), List.of(), false));
            }
            case TRASH_RESTORED -> {
                TrashRestored restored = TrashRestored.decode(event.payload());
                items.computeIfPresent(new Key(restored.laneId(), restored.epoch()), (key, item) -> {
                    List<UUID> groups = new java.util.ArrayList<>(item.restoredInto());
                    if (!groups.contains(restored.workGroupId())) {
                        groups.add(restored.workGroupId());
                    }
                    return new Item(item.trashed(), item.writerNodeId(), groups, item.emptied());
                });
            }
            case TRASH_EMPTIED -> {
                TrashEmptied emptied = TrashEmptied.decode(event.payload());
                items.computeIfPresent(new Key(emptied.laneId(), emptied.epoch()),
                        (key, item) -> new Item(item.trashed(), item.writerNodeId(), item.restoredInto(), true));
            }
            default -> {
            }
        }
    }

    /**
     * Returns every piece of trashed work, oldest first, emptied included.
     *
     * @return the items
     */
    public synchronized List<Item> items() {
        return List.copyOf(items.values());
    }

    /**
     * Returns one piece of trashed work.
     *
     * @param laneId the lane
     * @param epoch  the lane's epoch
     * @return the item, or empty when that work was never trashed
     */
    public synchronized Optional<Item> item(UUID laneId, long epoch) {
        return Optional.ofNullable(items.get(new Key(laneId, epoch)));
    }

    /**
     * Returns the trashed work a work group was created to bring back.
     *
     * @param workGroupId the work group
     * @return the item, or empty when the group restores nothing
     */
    public synchronized Optional<Item> restoredInto(UUID workGroupId) {
        return items.values().stream().filter(item -> item.restoredInto().contains(workGroupId)).findFirst();
    }

    private Item named(UUID laneId, long epoch, ApplyMode mode) throws IOException {
        Item item = items.get(new Key(laneId, epoch));
        if (item == null) {
            throw mode.checking() ? new CoordinationException("TRASH_ITEM_NOT_FOUND") : new MissingReferenceException("TRASH_ITEM_NOT_FOUND");
        }
        return item;
    }
}
