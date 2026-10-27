package org.synesis.workspace.application.project.execution.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The reason and fix chosen for a bubblewrap probe that ran and failed. Nothing here starts bwrap. */
class LinuxBubblewrapSandboxTest {

    private static final String UID_MAP = "bwrap: setting up uid map: Permission denied";

    @Test
    void restrictedUserNamespacesNameTheNarrowAppArmorProfile() {
        SandboxAvailability availability = LinuxBubblewrapSandbox.failedProbe(1, UID_MAP, true);

        assertFalse(availability.available());
        assertTrue(availability.reason().contains("apparmor_restrict_unprivileged_userns"));
        assertTrue(availability.fix().contains("/etc/apparmor.d/bwrap"));
        assertTrue(availability.fix().contains("profile bwrap /usr/bin/bwrap flags=(unconfined) { userns, }"));
        assertTrue(availability.fix().contains("sudo systemctl reload apparmor"));
        assertFalse(availability.fix().contains("sysctl -w"), "turning the sysctl off must never be suggested");
        assertFalse(availability.fix().contains("= 0"));
    }

    @Test
    void theSameErrorWithoutTheRestrictionKeepsTheInstallFix() {
        SandboxAvailability availability = LinuxBubblewrapSandbox.failedProbe(1, UID_MAP, false);

        assertTrue(availability.fix().startsWith("install bubblewrap"));
        assertFalse(availability.reason().contains("apparmor"));
    }

    @Test
    void anotherFailureUnderTheRestrictionKeepsTheInstallFix() {
        SandboxAvailability availability = LinuxBubblewrapSandbox.failedProbe(11, "", true);

        assertTrue(availability.fix().startsWith("install bubblewrap"));
        assertEquals("the bubblewrap probe failed with exit 11", availability.reason());
    }
}
