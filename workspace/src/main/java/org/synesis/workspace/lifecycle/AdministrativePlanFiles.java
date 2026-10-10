package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.filesystem.AtomicFiles;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * The shared mechanics of the cleanup, reconciliation, and repair plan stores: a plan is one JSON
 * file named after its plan id, and its {@code contentHash} is the SHA-256 of the same JSON
 * serialized with {@code contentHash} set to {@value #UNSIGNED}.
 *
 * <p>Each store owns its field mapping and id prefix. Key order in the plan map is part of the
 * hash input, so a store must not reorder the keys it already writes.</p>
 *
 * @since 1.0
 */
public final class AdministrativePlanFiles {

    private static final String UNSIGNED = "UNSIGNED";

    private AdministrativePlanFiles() {
    }

    /**
     * Parses the raw JSON of a stored plan.
     *
     * @param <P> plan type
     */
    @FunctionalInterface
    public interface PlanParser<P> {

        /**
         * Parses a stored plan.
         *
         * @param rawJson stored JSON text
         * @return the plan
         * @throws IOException when the text is not a valid plan
         */
        P parse(String rawJson) throws IOException;
    }

    /**
     * Computes the content hash of a plan.
     *
     * @param plan the plan's serializable map, in persisted key order
     * @return lower-case SHA-256 of the map serialized with {@code contentHash} unsigned
     */
    public static String contentHash(Map<String, Object> plan) {
        Map<String, Object> unsigned = new LinkedHashMap<>(plan);
        unsigned.put("contentHash", UNSIGNED);
        return Sha256.hex(ProviderJson.write(unsigned));
    }

    /**
     * Writes a signed plan as {@code <planId>.json}, replacing the file only once it is complete.
     *
     * @param plansDirectory directory of the plan kind
     * @param planId         plan identifier, also the file name
     * @param signedPlan     the plan's serializable map, carrying its content hash
     * @throws IOException when the plan cannot be written
     */
    public static void write(Path plansDirectory, String planId, Map<String, Object> signedPlan) throws IOException {
        Files.createDirectories(plansDirectory);
        Path temporary = plansDirectory.resolve(planId + ".json.tmp");
        Files.writeString(temporary, ProviderJson.write(signedPlan), StandardCharsets.UTF_8);
        AtomicFiles.replaceFromTemporary(temporary, plansDirectory.resolve(planId + ".json"));
    }

    /**
     * Loads a stored plan and verifies its identity, its workspace, and its content hash.
     *
     * @param <P>            plan type
     * @param controlRoot    normalized control project root path
     * @param plansDirectory directory of the plan kind
     * @param label          plan kind used in error messages, such as {@code Cleanup}
     * @param planId         requested plan identifier
     * @param parser         reads the stored JSON into the plan
     * @param toMap          serializes the plan back to its persisted key order
     * @return the verified plan
     * @throws IOException when the plan is missing, unreadable, belongs to another workspace, or
     *                     fails its content hash
     */
    public static <P> P load(Path controlRoot, Path plansDirectory, String label, String planId, PlanParser<P> parser, Function<P, Map<String, Object>> toMap) throws IOException {
        Path planFile = plansDirectory.resolve(planId + ".json");
        if (!Files.exists(planFile)) {
            throw new IOException(label + " plan not found: " + planId);
        }

        P plan = parser.parse(Files.readString(planFile, StandardCharsets.UTF_8));
        Map<String, Object> stored = toMap.apply(plan);

        if (!planId.equals(stored.get("planId"))) {
            throw new IOException("Plan ID mismatch in store: expected " + planId + " but found " + stored.get("planId"));
        }
        Path expectedWorkspace = ProjectAdministrativeLayout.workspaceRoot(controlRoot);
        if (!expectedWorkspace.toString().equals(stored.get("externalWorkspaceRoot"))) {
            throw new IOException("External workspace root mismatch in plan: " + stored.get("externalWorkspaceRoot"));
        }
        if (!contentHash(stored).equals(stored.get("contentHash"))) {
            throw new IOException(label + " plan content hash integrity verification failed for " + planId);
        }
        return plan;
    }
}
