package org.synesis.workspace.application.project.execution.sandbox;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Keeps a file or folder open for reading without sharing delete access, so that nothing can delete it or rename it
 * while the hold lasts.
 *
 * <p>A label that stops a low-integrity process from writing a file does not stop it from deleting that file or
 * renaming it when the folder around it is writable: the delete is checked against the folder. Replacing a file
 * Git trusts ({@code .git}, {@code gitdir}, {@code commondir}, provider configuration) by deleting and recreating it
 * would defeat the label. An open handle without delete sharing refuses that, for any process, for as long as it is
 * held.</p>
 */
@SuppressWarnings("restricted")
final class WindowsFileHold implements AutoCloseable {

    private static final int GENERIC_READ = 0x80000000;
    private static final int SHARE_READ_WRITE = 0x00000001 | 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int BACKUP_SEMANTICS_AND_REPARSE_POINT = 0x02000000 | 0x00200000;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final MethodHandle CREATE_FILE = LINKER.downcallHandle(KERNEL32.find("CreateFileW").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE_HANDLE = LINKER.downcallHandle(KERNEL32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private final MemorySegment handle;

    private WindowsFileHold(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * Opens the path and holds it.
     *
     * @param path existing file or folder; a link is held itself, not its target
     * @return the hold, to be closed when the run ends
     * @throws IOException if the path cannot be opened without delete sharing, for instance because another process
     *                     already has it open for deletion
     */
    static WindowsFileHold open(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(JAVA_CHAR, (path.toAbsolutePath() + "\0").toCharArray());
            MemorySegment handle = (MemorySegment) CREATE_FILE.invoke(name, GENERIC_READ, SHARE_READ_WRITE, MemorySegment.NULL, OPEN_EXISTING, BACKUP_SEMANTICS_AND_REPARSE_POINT, MemorySegment.NULL);
            if (handle.address() == -1L) {
                throw new IOException("cannot hold " + path + " against deletion");
            }
            return new WindowsFileHold(handle);
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("hold call failed", failure);
        }
    }

    @Override
    public void close() {
        try {
            int ignored = (int) CLOSE_HANDLE.invoke(handle);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
