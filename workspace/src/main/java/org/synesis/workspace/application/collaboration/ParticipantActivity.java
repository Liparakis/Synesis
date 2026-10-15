package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * The time of each participant's latest verified MCP call, kept beside the session leases instead of
 * in the coordination log.
 *
 * <p>That time is current state, not history: appending a signed event for every call made
 * heartbeats most of the log. One small file per participant holds it, replaced atomically, so any
 * MCP server process can write it and the run controller can read it without a lock and without the
 * project append lock. Heartbeat events in logs written earlier still replay into
 * {@link Participant#lastVerifiedActivity()}; {@link #overlay} takes the later of the two.</p>
 */
public final class ParticipantActivity {

    /**
     * Participant handles are {@code agt_} plus a UUID; anything else is never a file name here.
     */
    private static final Pattern FILE_SAFE = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private ParticipantActivity() {
    }

    /**
     * Records a verified call by the participant at the current time.
     *
     * @param controlRoot control project root
     * @param participant participant handle
     * @throws IOException when the file cannot be written
     */
    public static void record(Path controlRoot, String participant) throws IOException {
        if (!FILE_SAFE.matcher(participant).matches()) {
            throw new IllegalArgumentException("participant handle is not a safe file name");
        }
        Path directory = ProjectAdministrativeLayout.participantActivity(controlRoot);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, participant + '-', ".tmp");
        Path target = directory.resolve(participant);
        try {
            Files.writeString(temporary, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Gives each participant the later of its logged activity time and its recorded call time.
     *
     * <p>A participant with no recorded call keeps the logged time, and an unreadable file counts as
     * no record, so a reader never fails because a writer is mid-replace.</p>
     *
     * @param controlRoot  control project root
     * @param participants participants replayed from the log
     * @return the same participants with the activity time applied
     */
    public static List<Participant> overlay(Path controlRoot, List<Participant> participants) {
        Path directory = ProjectAdministrativeLayout.participantActivity(controlRoot);
        if (!Files.isDirectory(directory)) {
            return participants;
        }
        return participants.stream().map(participant -> {
            long recorded = read(directory, participant.id());
            return recorded <= participant.lastVerifiedActivity() ? participant
                    : new Participant(participant.id(), participant.provider(), participant.goal(), participant.state(),
                            recorded, participant.claims(), participant.recoverySnapshotReference());
        }).toList();
    }

    private static long read(Path directory, String participant) {
        if (!FILE_SAFE.matcher(participant).matches()) {
            return 0L;
        }
        try {
            return Long.parseLong(Files.readString(directory.resolve(participant), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException none) {
            return 0L;
        }
    }
}
