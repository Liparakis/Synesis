package org.synesis.coordination.domain.prediction;

/**
 * How a projection takes one log entry (ADR-0098 step 4, "Checking is separate from applying").
 *
 * <p>Every computer writes only its own log and also applies copies of the other computers' logs, each in its own
 * order but interleaved in whatever order they arrive. A rule that refuses an entry can therefore be judged only by
 * the computer that writes the entry, against what that computer has seen. An entry written elsewhere is a signed
 * fact: applying it never refuses it, or the copies would stop agreeing. ADR-0100's table says how each rule of a
 * projection counts when an entry arrives.</p>
 */
public enum ApplyMode {

    /**
     * This computer's own new entry, judged by every rule before it is written. A projection applies it to a copy
     * and throws the refusal code, as before step 4.
     */
    CHECK,

    /**
     * An entry of another computer's log, applied here for the first time, on a copy. Revision and epoch checks
     * are the writer's and are skipped; two facts about one thing both stand. An entry that names something this
     * computer has not seen yet throws {@link org.synesis.coordination.exception.MissingReferenceException}, and
     * the store holds it back until that arrives.
     */
    ARRIVAL,

    /**
     * An entry this computer has already taken, in its applied order: the live projections, and every open of the
     * log. It is applied as {@link #ARRIVAL} would, with nothing left to refuse.
     */
    REPLAY;

    /**
     * Tells whether the writer's rules are judged.
     *
     * @return true for {@link #CHECK}
     */
    public boolean checking() {
        return this == CHECK;
    }
}
