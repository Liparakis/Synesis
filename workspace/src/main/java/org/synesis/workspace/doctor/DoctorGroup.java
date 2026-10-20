package org.synesis.workspace.doctor;

/**
 * The part of a computer's setup a doctor finding is about (ADR-0103, decision 4). The three named groups are what a
 * person reads in the report; findings that existed before them stay in {@link #PROJECT}.
 *
 * @since 1.0
 */
public enum DoctorGroup {
    /**
     * The project itself: its identity, durable state, locks and migrations.
     */
    PROJECT("project"),

    /**
     * This computer: Git, the connected agents and their managed files, the installed launcher.
     */
    ENVIRONMENT("environment"),

    /**
     * The connection to other computers of a shared project: its runtime and the firewall rule Link needs.
     */
    NETWORK("network"),

    /**
     * The work in progress: open operator requests, sessions that went quiet, worktrees ready for cleanup.
     */
    WORK_GROUPS("work_groups");

    private final String value;

    DoctorGroup(String value) {
        this.value = value;
    }

    /**
     * Returns the stable machine-readable name, which prefixes the group's output keys in upper case.
     *
     * @return lowercase name
     */
    public String value() {
        return value;
    }
}
