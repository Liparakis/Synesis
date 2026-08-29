package org.synesis.workspace.lifecycle.command;

import java.nio.file.Path;

/**
 * Small forked-process fixture used to prove OS-level command lock exclusion.
 */
public final class CommandLockProbe {

    private CommandLockProbe() {
    }

    /**
     * Runs the holder or contender fixture.
     *
     * @param arguments mode and lock path
     */
    static void main(String[] arguments) {
        if (arguments.length != 2) {
            System.exit(64);
        }
        try (CommandPermanentLock lock = CommandPermanentLock.open(Path.of(arguments[1]))) {
            if (!lock.isHeld()) {
                System.exit(73);
            }
            if ("hold".equals(arguments[0])) {
                System.out.println("ready");
                System.out.flush();
                if (System.in.read() < 0) {
                    System.exit(0);
                }
            } else if (!"try".equals(arguments[0])) {
                System.exit(64);
            }
        } catch (Exception failure) {
            System.err.println(failure.getMessage());
            System.exit(73);
        }
    }
}
