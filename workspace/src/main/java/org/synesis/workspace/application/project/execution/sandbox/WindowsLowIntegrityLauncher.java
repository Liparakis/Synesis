package org.synesis.workspace.application.project.execution.sandbox;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Starts a process on Windows with a low-integrity copy of the current token, inside its own kill-on-close job
 * (ADR-0104, "Windows: a low-integrity process").
 *
 * <p>The token is the current user's, duplicated and then lowered to the Low mandatory level. No privilege is
 * needed for that: it is how a browser starts a low-rights helper. Only the child's three standard handles are
 * inherited (a handle list), so no other handle of this JVM reaches the command. The job is what ends the whole
 * tree: {@link LowIntegrityProcess#destroyForcibly()} terminates it, and closing the process terminates it too.</p>
 *
 * <p>This is a second, smaller copy of the launch path of the Codex job-object supervisor on purpose: that class
 * lives in {@code lifecycle.codex}, which depends on the application packages, and depending on it from here would
 * add a package cycle the architecture test forbids.</p>
 */
@SuppressWarnings("restricted")
final class WindowsLowIntegrityLauncher {

    private static final int CREATE_SUSPENDED = 0x00000004;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int HANDLE_FLAG_INHERIT = 0x00000001;
    private static final int PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int TOKEN_INTEGRITY_LEVEL = 25;
    private static final int TOKEN_DUPLICATE_AND_QUERY = 0x0002 | 0x0008;
    private static final int MAXIMUM_ALLOWED = 0x02000000;
    private static final int SE_GROUP_INTEGRITY = 0x00000020;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 0x00000102;
    private static final long STARTUPINFOEX_SIZE = 112;
    private static final long PROCESS_INFORMATION_SIZE = 24;
    private static final long JOB_LIMITS_SIZE = 144;
    /**
     * The SID S-1-16-4096 (Low mandatory level), written out: revision 1, one sub-authority, authority 16.
     */
    private static final byte[] LOW_LABEL_SID = {1, 1, 0, 0, 0, 0, 0, 16, 0, 0x10, 0, 0};
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();

    private WindowsLowIntegrityLauncher() {
    }

    /**
     * Starts the command suspended in a new job, resumes it once it is in the job, and returns the process.
     *
     * @param argv             executable and arguments
     * @param workingDirectory working directory
     * @param environment      complete environment
     * @return the running low-integrity process, which the caller closes
     * @throws IOException if the token, the job or the process cannot be created
     */
    static LowIntegrityProcess start(List<String> argv, Path workingDirectory, Map<String, String> environment) throws IOException {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IOException("empty command");
        }
        Native api = Native.INSTANCE;
        MemorySegment token = null;
        MemorySegment job = null;
        MemorySegment[] pipes = null;
        try (Arena arena = Arena.ofConfined()) {
            token = api.lowIntegrityToken(arena);
            job = api.createKillOnCloseJob(arena);
            pipes = api.createPipes(arena);
            MemorySegment childEnds = arena.allocate(24, 8);
            childEnds.set(ADDRESS, 0, pipes[0]);
            childEnds.set(ADDRESS, 8, pipes[2]);
            childEnds.set(ADDRESS, 16, pipes[4]);
            MemorySegment attributes = api.attributeList(arena, childEnds);
            MemorySegment startup = arena.allocate(STARTUPINFOEX_SIZE, 8);
            startup.set(JAVA_INT, 0, (int) STARTUPINFOEX_SIZE);
            startup.set(JAVA_INT, 60, STARTF_USESTDHANDLES);
            startup.set(ADDRESS, 80, pipes[0]);
            startup.set(ADDRESS, 88, pipes[2]);
            startup.set(ADDRESS, 96, pipes[4]);
            startup.set(ADDRESS, 104, attributes);
            MemorySegment info = arena.allocate(PROCESS_INFORMATION_SIZE, 8);
            api.createProcessAsUser(arena, token, utf16(arena, commandLine(argv)), utf16(arena, environmentBlock(environment)),
                    utf16(arena, workingDirectory.toAbsolutePath().normalize().toString()), startup, info);
            MemorySegment processHandle = info.get(ADDRESS, 0);
            MemorySegment threadHandle = info.get(ADDRESS, 8);
            try {
                api.check(api.assignProcessToJob(job, processHandle), "AssignProcessToJobObject");
                api.check(api.resumeThread(threadHandle) == -1 ? 0 : 1, "ResumeThread");
            } catch (IOException failure) {
                api.terminateJob(job);
                api.closeHandle(processHandle);
                throw failure;
            } finally {
                api.closeHandle(threadHandle);
            }
            api.closeHandle(pipes[0]);
            api.closeHandle(pipes[2]);
            api.closeHandle(pipes[4]);
            LowIntegrityProcess process = new LowIntegrityProcess(api, job, processHandle, Integer.toUnsignedLong(info.get(JAVA_INT, 16)), pipes[1], pipes[3], pipes[5]);
            job = null;
            pipes = null;
            return process;
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("low-integrity launch failed", failure);
        } finally {
            api.closeHandle(token);
            api.closeHandle(job);
            if (pipes != null) {
                for (MemorySegment pipe : pipes) {
                    api.closeHandle(pipe);
                }
            }
        }
    }

    private static MemorySegment utf16(Arena arena, String value) {
        return arena.allocateFrom(JAVA_CHAR, (value + "\0").toCharArray());
    }

    private static String environmentBlock(Map<String, String> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)).map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\0", "", "\0\0"));
    }

    private static String commandLine(List<String> command) {
        return command.stream().map(WindowsLowIntegrityLauncher::quote).collect(Collectors.joining(" "));
    }

    /**
     * Quotes one argument the way the C runtime and {@code CommandLineToArgvW} read it back.
     */
    static String quote(String value) {
        if (!value.isEmpty() && value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int slashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\') {
                slashes++;
            } else if (character == '"') {
                quoted.repeat("\\", slashes * 2 + 1).append('"');
                slashes = 0;
            } else {
                quoted.repeat("\\", slashes).append(character);
                slashes = 0;
            }
        }
        quoted.repeat("\\", slashes * 2).append('"');
        return quoted.toString();
    }

    /**
     * A running low-integrity process. Closing it ends the job, so nothing the command started outlives the run.
     */
    static final class LowIntegrityProcess extends Process implements AutoCloseable {

        private final Native api;
        private final MemorySegment job;
        private final MemorySegment processHandle;
        private final long pid;
        private final PipeOutput stdin;
        private final PipeInput stdout;
        private final PipeInput stderr;
        private boolean closed;

        private LowIntegrityProcess(Native api, MemorySegment job, MemorySegment processHandle, long pid, MemorySegment stdin, MemorySegment stdout, MemorySegment stderr) {
            this.api = api;
            this.job = job;
            this.processHandle = processHandle;
            this.pid = pid;
            this.stdin = new PipeOutput(api, stdin);
            this.stdout = new PipeInput(api, stdout);
            this.stderr = new PipeInput(api, stderr);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (api.waitForSingleObject(processHandle, -1) != WAIT_OBJECT_0) {
                throw new InterruptedException("WaitForSingleObject failed");
            }
            return exitValue();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long millis = Math.min(unit.toMillis(timeout), Integer.MAX_VALUE - 1L);
            int result = api.waitForSingleObject(processHandle, (int) Math.max(millis, 0));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            return result == WAIT_OBJECT_0;
        }

        @Override
        public int exitValue() {
            if (api.waitForSingleObject(processHandle, 0) == WAIT_TIMEOUT) {
                throw new IllegalThreadStateException("process is still running");
            }
            return api.exitCode(processHandle);
        }

        @Override
        public boolean isAlive() {
            return api.waitForSingleObject(processHandle, 0) == WAIT_TIMEOUT;
        }

        @Override
        public long pid() {
            return pid;
        }

        /**
         * Ends the whole job, not only the root: a command that forked is killed with its children.
         */
        @Override
        public void destroy() {
            api.terminateJob(job);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean supportsNormalTermination() {
            return false;
        }

        /**
         * Ends the job and releases every handle. Safe to call twice.
         */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            api.terminateJob(job);
            api.waitForSingleObject(processHandle, 5_000);
            stdin.close();
            stdout.close();
            stderr.close();
            api.closeHandle(processHandle);
            api.closeHandle(job);
        }
    }

    private static final class PipeInput extends InputStream {

        private final Native api;
        private final MemorySegment handle;
        private volatile boolean closed;

        private PipeInput(Native api, MemorySegment handle) {
            this.api = api;
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(length, 1);
                MemorySegment read = arena.allocate(JAVA_INT);
                int error = api.readFile(arena, handle, buffer, length, read);
                if (error != 0) {
                    if (error == ERROR_BROKEN_PIPE || closed) {
                        return -1;
                    }
                    throw Native.failed("ReadFile", error);
                }
                int count = read.get(JAVA_INT, 0);
                MemorySegment.copy(buffer, JAVA_BYTE, 0, bytes, offset, count);
                return count;
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                api.closeHandle(handle);
            }
        }
    }

    private static final class PipeOutput extends OutputStream {

        private final Native api;
        private final MemorySegment handle;
        private volatile boolean closed;

        private PipeOutput(Native api, MemorySegment handle) {
            this.api = api;
            this.handle = handle;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) {
                throw new IOException("pipe closed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(length, 1);
                MemorySegment.copy(bytes, offset, buffer, JAVA_BYTE, 0, length);
                MemorySegment written = arena.allocate(JAVA_INT);
                if (api.writeFile(handle, buffer, length, written) == 0 || written.get(JAVA_INT, 0) != length) {
                    throw new IOException("WriteFile failed");
                }
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                api.closeHandle(handle);
            }
        }
    }

    /**
     * The Win32 calls, bound once. A failed call reports the last error captured by the call itself, because a
     * separate {@code GetLastError} call can be overwritten by the JVM in between.
     */
    private static final class Native {

        private static final Native INSTANCE = new Native();

        private final Linker linker = Linker.nativeLinker();
        private final SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private final SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
        private final Linker.Option capture = Linker.Option.captureCallState("GetLastError");
        private final MethodHandle openProcessToken = bind(advapi32, "OpenProcessToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private final MethodHandle duplicateTokenEx = bind(advapi32, "DuplicateTokenEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private final MethodHandle setTokenInformation = bind(advapi32, "SetTokenInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        private final MethodHandle createProcessAsUser = bindCapturing(advapi32, "CreateProcessAsUserW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private final MethodHandle getCurrentProcess = bind(kernel32, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        private final MethodHandle createJobObject = bind(kernel32, "CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        private final MethodHandle setInformationJobObject = bind(kernel32, "SetInformationJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        private final MethodHandle assignProcessToJobObject = bind(kernel32, "AssignProcessToJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle terminateJobObject = bind(kernel32, "TerminateJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private final MethodHandle createPipe = bind(kernel32, "CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        private final MethodHandle setHandleInformation = bind(kernel32, "SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        private final MethodHandle initializeAttributeList = bind(kernel32, "InitializeProcThreadAttributeList", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private final MethodHandle updateAttribute = bind(kernel32, "UpdateProcThreadAttribute",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        private final MethodHandle resumeThread = bind(kernel32, "ResumeThread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private final MethodHandle waitForSingleObject = bind(kernel32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private final MethodHandle getExitCodeProcess = bind(kernel32, "GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle readFile = bindCapturing(kernel32, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle writeFile = bind(kernel32, "WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle closeHandle = bind(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        private MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(library.find(name).orElseThrow(), descriptor);
        }

        private MethodHandle bindCapturing(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(library.find(name).orElseThrow(), descriptor, capture);
        }

        static IOException failed(String call, int error) {
            return new IOException(call + " failed, win32=" + error);
        }

        private void check(int result, String call) throws IOException {
            if (result == 0) {
                throw failed(call, -1);
            }
        }

        private <T> T invoke(MethodHandle handle, Object... arguments) {
            try {
                @SuppressWarnings("unchecked")
                T result = (T) handle.invokeWithArguments(arguments);
                return result;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        }

        /**
         * The current user's token, as a primary token at the Low mandatory level.
         */
        MemorySegment lowIntegrityToken(Arena arena) throws IOException {
            MemorySegment process = invoke(getCurrentProcess);
            MemorySegment own = arena.allocate(ADDRESS);
            if ((int) invoke(openProcessToken, process, TOKEN_DUPLICATE_AND_QUERY, own) == 0) {
                throw failed("OpenProcessToken", -1);
            }
            MemorySegment copy = arena.allocate(ADDRESS);
            int duplicated = invoke(duplicateTokenEx, own.get(ADDRESS, 0), MAXIMUM_ALLOWED, MemorySegment.NULL, 2, 1, copy);
            closeHandle(own.get(ADDRESS, 0));
            if (duplicated == 0) {
                throw failed("DuplicateTokenEx", -1);
            }
            MemorySegment lowered = copy.get(ADDRESS, 0);
            MemorySegment label = arena.allocate(16 + LOW_LABEL_SID.length, 8);
            MemorySegment sid = label.asSlice(16, LOW_LABEL_SID.length);
            MemorySegment.copy(LOW_LABEL_SID, 0, sid, JAVA_BYTE, 0, LOW_LABEL_SID.length);
            label.set(ADDRESS, 0, sid);
            label.set(JAVA_INT, 8, SE_GROUP_INTEGRITY);
            if ((int) invoke(setTokenInformation, lowered, TOKEN_INTEGRITY_LEVEL, label, (int) label.byteSize()) == 0) {
                closeHandle(lowered);
                throw failed("SetTokenInformation(TokenIntegrityLevel)", -1);
            }
            return lowered;
        }

        MemorySegment createKillOnCloseJob(Arena arena) throws IOException {
            MemorySegment job = invoke(createJobObject, MemorySegment.NULL, MemorySegment.NULL);
            if (job.address() == 0) {
                throw failed("CreateJobObjectW", -1);
            }
            MemorySegment limits = arena.allocate(JOB_LIMITS_SIZE, 8);
            limits.set(JAVA_INT, 16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            if ((int) invoke(setInformationJobObject, job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits, (int) JOB_LIMITS_SIZE) == 0) {
                closeHandle(job);
                throw failed("SetInformationJobObject", -1);
            }
            return job;
        }

        /**
         * Three pipes: the child's ends first (stdin read, stdout write, stderr write, inheritable), then ours.
         * Returns {childIn, parentIn, childOut, parentOut, childErr, parentErr}.
         */
        MemorySegment[] createPipes(Arena arena) throws IOException {
            MemorySegment security = arena.allocate(24, 8);
            security.set(JAVA_INT, 0, 24);
            security.set(JAVA_INT, 16, 1);
            MemorySegment[] ends = new MemorySegment[6];
            for (int pipe = 0; pipe < 3; pipe++) {
                MemorySegment read = arena.allocate(ADDRESS);
                MemorySegment write = arena.allocate(ADDRESS);
                if ((int) invoke(createPipe, read, write, security, 0) == 0) {
                    for (MemorySegment end : ends) {
                        closeHandle(end);
                    }
                    throw failed("CreatePipe", -1);
                }
                // The child reads stdin and writes stdout and stderr; this side holds the opposite end.
                ends[pipe * 2] = pipe == 0 ? read.get(ADDRESS, 0) : write.get(ADDRESS, 0);
                ends[pipe * 2 + 1] = pipe == 0 ? write.get(ADDRESS, 0) : read.get(ADDRESS, 0);
                if ((int) invoke(setHandleInformation, ends[pipe * 2 + 1], HANDLE_FLAG_INHERIT, 0) == 0) {
                    for (MemorySegment end : ends) {
                        closeHandle(end);
                    }
                    throw failed("SetHandleInformation", -1);
                }
            }
            return ends;
        }

        /**
         * A process attribute list that restricts handle inheritance to the three given handles.
         */
        MemorySegment attributeList(Arena arena, MemorySegment handles) throws IOException {
            MemorySegment size = arena.allocate(JAVA_LONG);
            invoke(initializeAttributeList, MemorySegment.NULL, 1, 0, size);
            MemorySegment list = arena.allocate(size.get(JAVA_LONG, 0), 8);
            if ((int) invoke(initializeAttributeList, list, 1, 0, size) == 0) {
                throw failed("InitializeProcThreadAttributeList", -1);
            }
            if ((int) invoke(updateAttribute, list, 0, (long) PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, handles.byteSize(), MemorySegment.NULL, MemorySegment.NULL) == 0) {
                throw failed("UpdateProcThreadAttribute", -1);
            }
            return list;
        }

        void createProcessAsUser(Arena arena, MemorySegment token, MemorySegment commandLine, MemorySegment environment, MemorySegment directory, MemorySegment startup,
                MemorySegment info) throws IOException {
            MemorySegment state = arena.allocate(CAPTURE);
            int created = invoke(createProcessAsUser, state, token, MemorySegment.NULL, commandLine, MemorySegment.NULL, MemorySegment.NULL, 1,
                    CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT, environment, directory, startup, info);
            if (created == 0) {
                throw failed("CreateProcessAsUserW", state.get(JAVA_INT, CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))));
            }
        }

        int assignProcessToJob(MemorySegment job, MemorySegment process) {
            return invoke(assignProcessToJobObject, job, process);
        }

        int resumeThread(MemorySegment thread) {
            return invoke(resumeThread, thread);
        }

        void terminateJob(MemorySegment job) {
            invoke(terminateJobObject, job, 1);
        }

        int waitForSingleObject(MemorySegment handle, int millis) {
            return invoke(waitForSingleObject, handle, millis);
        }

        int exitCode(MemorySegment process) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = arena.allocate(JAVA_INT);
                invoke(getExitCodeProcess, process, result);
                return result.get(JAVA_INT, 0);
            }
        }

        /**
         * Reads from a pipe and returns zero on success, otherwise the Win32 error of the failed read.
         */
        int readFile(Arena arena, MemorySegment handle, MemorySegment buffer, int length, MemorySegment read) {
            MemorySegment state = arena.allocate(CAPTURE);
            int result = invoke(readFile, state, handle, buffer, length, read, MemorySegment.NULL);
            return result != 0 ? 0 : Math.max(state.get(JAVA_INT, CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))), 1);
        }

        int writeFile(MemorySegment handle, MemorySegment buffer, int length, MemorySegment written) {
            return invoke(writeFile, handle, buffer, length, written, MemorySegment.NULL);
        }

        void closeHandle(MemorySegment handle) {
            if (handle != null && handle.address() != 0) {
                invoke(closeHandle, handle);
            }
        }
    }
}
