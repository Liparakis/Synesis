package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.NodeIdentity;

/**
 * Moves a step-5 project's membership from {@code replication.properties} into the logs, once, when its server starts
 * (ADR-0098 step 6, designer item 14). An owner's file ({@code role=leader}) becomes {@code PROJECT_SHARED} with no
 * recovery key and an accepted join for every member it named; a member's ({@code role=member}) keeps its inviter as a
 * computer it dialed and waits for the owner's entries. Both files are then written as {@code role=shared}, keeping
 * the code remote. A shared file, or none, is left as it is.
 */
public final class SharedSettingsMigration {

    private SharedSettingsMigration() {
    }

    /**
     * Migrates a coordination folder's settings, if they are a step-5 file.
     *
     * @param coordination the coordination folder
     * @param store        its log
     * @param identity     this computer
     * @return whether anything was migrated
     * @throws IOException              when an entry or the file cannot be written; the step-5 file is then kept
     * @throws GeneralSecurityException when signing fails
     */
    public static boolean migrate(Path coordination, PredictionEventStore store, NodeIdentity identity) throws IOException, GeneralSecurityException {
        Optional<ReplicationSettings> found = ReplicationSettings.read(coordination);
        if (found.isEmpty() || found.get().shared()) {
            return false;
        }
        ReplicationSettings settings = found.get();
        if (!settings.isMember()) {
            new MembershipService(store, identity).migrateOwner(settings.members());
        } else if (!org.synesis.coordination.persistence.ReplicationPeers.read(coordination).dialed().containsKey(settings.leaderNodeId())) {
            // A member that never dialed its leader (set up with 'project follow') keeps its file, which names whom it
            // trusts, until it joins by a link.
            return false;
        }
        // The file goes last: a step-5 file still present means the migration did not finish, and runs again.
        ReplicationSettings.sharedProject(settings.codeRemote()).write(coordination);
        return true;
    }
}
