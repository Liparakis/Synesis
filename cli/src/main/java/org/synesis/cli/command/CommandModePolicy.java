package org.synesis.cli.command;

/**
 * Shared selection policy for commands with mutually exclusive operation modes.
 */
public final class CommandModePolicy {

    private CommandModePolicy() {
    }

    /**
     * Reports whether more than one operation mode was selected.
     *
     * @param selectedModes option-presence flags for each operation mode
     * @return {@code true} when the invocation is ambiguous
     */
    public static boolean hasConflictingModes(boolean... selectedModes) {
        int selected = 0;
        for (boolean mode : selectedModes) {
            if (mode && ++selected > 1) {
                return true;
            }
        }
        return false;
    }
}
