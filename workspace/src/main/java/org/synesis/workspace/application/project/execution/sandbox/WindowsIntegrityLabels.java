package org.synesis.workspace.application.project.execution.sandbox;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Sets and removes the mandatory integrity label of a file or folder without elevation.
 *
 * <p>A low-integrity process cannot write an object whose label is Medium or higher (the default for an unlabelled
 * object), and cannot read one whose Medium label also says no-read-up. A folder's label is inheritable, and
 * setting it on a folder rewrites the inherited label of everything below it in one call, so one call labels a
 * whole worktree. A label at or below the caller's own level needs only {@code WRITE_OWNER} on the object, which
 * the owner has through the inherited access list in a user profile. Where it is missing, this class adds that one
 * right for the owner and retries once, so it never widens access beyond what the owner could grant themselves.</p>
 */
@SuppressWarnings("restricted")
final class WindowsIntegrityLabels {

    /**
     * The levels this sandbox uses.
     */
    enum Level {
        LOW("LW", 0x1000),
        MEDIUM("ME", 0x2000);

        private final String alias;
        private final int rid;

        Level(String alias, int rid) {
            this.rid = rid;
            this.alias = alias;
        }
    }

    private static final int SE_FILE_OBJECT = 1;
    private static final int LABEL_SECURITY_INFORMATION = 0x10;
    private static final int ERROR_ACCESS_DENIED = 5;
    private static final int SYSTEM_MANDATORY_LABEL_ACE_TYPE = 0x11;
    private static final int OWNER_AND_DACL_SECURITY_INFORMATION = 0x1 | 0x4;
    private static final int DACL_SECURITY_INFORMATION = 0x4;
    private static final int WRITE_OWNER = 0x00080000;
    private static final int GRANT_ACCESS = 1;
    private static final int OBJECT_AND_CONTAINER_INHERIT = 3;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int SDDL_REVISION_1 = 1;
    /**
     * An access control list with no entries: setting it as the label removes the explicit label.
     */
    private static final byte[] EMPTY_ACL = {2, 0, 8, 0, 0, 0, 0, 0};
    private static final Map<String, MemorySegment> DESCRIPTORS = new HashMap<>();

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
    private static final MethodHandle CONVERT = bind("ConvertStringSecurityDescriptorToSecurityDescriptorW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_SACL = bind("GetSecurityDescriptorSacl", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_NAMED = bind("SetNamedSecurityInfoW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_NAMED = bind("GetNamedSecurityInfoW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_ENTRIES = bind("SetEntriesInAclW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle LOCAL_FREE =LINKER.downcallHandle(SymbolLookup.libraryLookup("kernel32", Arena.global()).find("LocalFree").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));

    private WindowsIntegrityLabels() {
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(ADVAPI32.find(name).orElseThrow(), descriptor);
    }

    /**
     * Labels a path (and, for a folder, everything below it that has no explicit label of its own).
     *
     * @param path   existing file or folder
     * @param level  the mandatory level of the label
     * @param noRead whether a process below the level also loses read access
     * @return as {@link #set(Path, Level, boolean, boolean)}
     * @throws IOException if the label cannot be set
     */
    static boolean set(Path path, Level level, boolean noRead) throws IOException {
        return set(path, level, noRead, true);
    }

    /**
     * Labels one path. A folder's label is inherited by what is created in it and by what is already in it, unless
     * {@code inherit} is false, in which case only the folder itself is labelled.
     *
     * @param path    existing file or folder
     * @param level   the mandatory level of the label
     * @param noRead  whether a process below the level also loses read access
     * @param inherit whether the label of a folder is passed down to its children
     * @return whether the owner lacked the right to label the path and was given it, which means the folder is not
     *         one whose children inherit that right and its children may not all have taken the label
     * @throws IOException if the label cannot be set
     */
    static boolean set(Path path, Level level, boolean noRead, boolean inherit) throws IOException {
        boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        String sddl = "S:(ML;" + (folder && inherit ? "OICI" : "") + ";" + (noRead ? "NR" : "") + "NW;;;" + level.alias + ")";
        return apply(path, folder, sddl(sddl));
    }

    /**
     * Labels a folder tree and proves every file and folder in it carries the label. Used where a missed file would
     * be a leak (no-read-up on hidden state): setting a folder's label rewrites its children's inherited labels, but
     * it skips, without an error, a child on which the caller lacks {@code WRITE_OWNER}, which happens in folders
     * outside the user's profile. Each such node is then labelled on its own. Links are not followed, so a link in
     * the tree never labels what it points to.
     *
     * @param root   existing folder or file
     * @param level  the mandatory level of the label
     * @param noRead whether a process below the level also loses read access
     * @param excluded paths inside the tree that carry a deliberate label of their own and are neither checked nor changed
     * @throws IOException if a node cannot be labelled or read back
     */
    static void setVerified(Path root, Level level, boolean noRead, java.util.Collection<Path> excluded) throws IOException {
        set(root, level, noRead);
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                return attributes.isSymbolicLink() || attributes.isOther() || excluded.contains(directory) ? java.nio.file.FileVisitResult.SKIP_SUBTREE : check(directory);
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                return attributes.isSymbolicLink() || attributes.isOther() || excluded.contains(file) ? java.nio.file.FileVisitResult.CONTINUE : check(file);
            }

            private java.nio.file.FileVisitResult check(Path node) throws IOException {
                if (!carries(node, level, noRead)) {
                    set(node, level, noRead);
                    if (!carries(node, level, noRead)) {
                        throw new IOException("cannot label " + node);
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Tells whether the path's own label is at the level and denies what the arguments say.
     */
    private static boolean carries(Path path, Level level, boolean noRead) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(JAVA_CHAR, (path.toAbsolutePath() + "\0").toCharArray());
            MemorySegment sacl = arena.allocate(ADDRESS);
            MemorySegment descriptor = arena.allocate(ADDRESS);
            int error = (int) GET_NAMED.invoke(name, SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, sacl, descriptor);
            if (error != 0) {
                throw new IOException("cannot read the integrity label of " + path + ", win32=" + error);
            }
            try {
                MemorySegment acl = sacl.get(ADDRESS, 0);
                if (acl.address() == 0) {
                    return false;
                }
                // ACL header is 8 bytes; the first entry is the label: type, flags, size, mask, then the SID.
                MemorySegment entry = acl.reinterpret(8 + 8 + 12 + 8).asSlice(8);
                int mask = entry.get(JAVA_INT, 4);
                int subAuthorities = entry.get(JAVA_BYTE, 9) & 0xFF;
                int rid = entry.get(JAVA_INT, 8 + 8 + (subAuthorities - 1) * 4L);
                int wanted = 1 | (noRead ? 2 : 0);
                return entry.get(JAVA_BYTE, 0) == SYSTEM_MANDATORY_LABEL_ACE_TYPE && rid == level.rid && (mask & wanted) == wanted;
            } finally {
                LOCAL_FREE.invoke(descriptor.get(ADDRESS, 0));
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("integrity label call failed", failure);
        }
    }

    /**
     * Removes the explicit label, so the path takes the label it inherits (none, or its parent's).
     *
     * @param path existing file or folder
     * @throws IOException if the label cannot be removed
     */
    static void remove(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment empty = arena.allocate(EMPTY_ACL.length, 8);
            MemorySegment.copy(EMPTY_ACL, 0, empty, JAVA_BYTE, 0, EMPTY_ACL.length);
            apply(path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), empty);
        }
    }

    private static boolean apply(Path path, boolean folder, MemorySegment sacl) throws IOException {
        int error = setNamed(path, sacl);
        boolean granted = false;
        if (error == ERROR_ACCESS_DENIED) {
            grantOwnerWriteOwner(path, folder);
            granted = true;
            error = setNamed(path, sacl);
        }
        if (error != 0) {
            throw new IOException("cannot set the integrity label of " + path + ", win32=" + error);
        }
        return granted;
    }

    private static int setNamed(Path path, MemorySegment sacl) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(JAVA_CHAR, (path.toAbsolutePath() + "\0").toCharArray());
            return (int) SET_NAMED.invoke(name, SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, sacl);
        } catch (Throwable failure) {
            throw new IOException("integrity label call failed", failure);
        }
    }

    /**
     * Adds {@code WRITE_OWNER} for the object's owner to its access list, keeping every entry that is there and its
     * inheritance (a rewrite through {@code java.nio}'s ACL view loses inherit-only and inherited flags), and lets it
     * pass down to what is inside a folder. Windows rewrites the inherited entries of the children in the same call,
     * so one call fixes a whole tree the owner created.
     */
    private static void grantOwnerWriteOwner(Path path, boolean folder) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(JAVA_CHAR, (path.toAbsolutePath() + "\0").toCharArray());
            MemorySegment owner = arena.allocate(ADDRESS);
            MemorySegment dacl = arena.allocate(ADDRESS);
            MemorySegment descriptor = arena.allocate(ADDRESS);
            int error = (int) GET_NAMED.invoke(name, SE_FILE_OBJECT, OWNER_AND_DACL_SECURITY_INFORMATION, owner, MemorySegment.NULL, dacl, MemorySegment.NULL, descriptor);
            if (error != 0) {
                throw new IOException("cannot read the access list of " + path + ", win32=" + error);
            }
            try {
                // EXPLICIT_ACCESS_W: permissions, mode, inheritance, then a TRUSTEE_W that names the owner by SID.
                MemorySegment access = arena.allocate(48, 8);
                access.set(JAVA_INT, 0, WRITE_OWNER);
                access.set(JAVA_INT, 4, GRANT_ACCESS);
                access.set(JAVA_INT, 8, folder ? OBJECT_AND_CONTAINER_INHERIT : 0);
                access.set(JAVA_INT, 28, TRUSTEE_IS_SID);
                access.set(ADDRESS, 40, owner.get(ADDRESS, 0));
                MemorySegment merged = arena.allocate(ADDRESS);
                error = (int) SET_ENTRIES.invoke(1, access, dacl.get(ADDRESS, 0), merged);
                if (error != 0) {
                    throw new IOException("cannot build the access list of " + path + ", win32=" + error);
                }
                try {
                    error = (int) SET_NAMED.invoke(name, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, merged.get(ADDRESS, 0), MemorySegment.NULL);
                    if (error != 0) {
                        throw new IOException("cannot give the owner the right to label " + path + ", win32=" + error);
                    }
                } finally {
                    LOCAL_FREE.invoke(merged.get(ADDRESS, 0));
                }
            } finally {
                LOCAL_FREE.invoke(descriptor.get(ADDRESS, 0));
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("access list call failed", failure);
        }
    }

    private static synchronized MemorySegment sddl(String text) throws IOException {
        MemorySegment cached = DESCRIPTORS.get(text);
        if (cached != null) {
            return cached;
        }
        try {
            Arena arena = Arena.global();
            MemorySegment descriptor = arena.allocate(ADDRESS);
            if ((int) CONVERT.invoke(arena.allocateFrom(JAVA_CHAR, (text + "\0").toCharArray()), SDDL_REVISION_1, descriptor, MemorySegment.NULL) == 0) {
                throw new IOException("integrity label text rejected: " + text);
            }
            MemorySegment present = arena.allocate(JAVA_INT);
            MemorySegment sacl = arena.allocate(ADDRESS);
            if ((int) GET_SACL.invoke(descriptor.get(ADDRESS, 0), present, sacl, arena.allocate(JAVA_INT)) == 0 || present.get(JAVA_INT, 0) == 0) {
                throw new IOException("integrity label text has no label: " + text);
            }
            MemorySegment label = sacl.get(ADDRESS, 0);
            DESCRIPTORS.put(text, label);
            return label;
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("integrity label call failed", failure);
        }
    }
}
