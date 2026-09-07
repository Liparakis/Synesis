package org.synesis.workspace.lifecycle.codex;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows Job Object implementation of the managed process-tree boundary.
 *
 * <p>The native launch path creates the root suspended, assigns it to a new
 * kill-on-close Job, and resumes its primary thread only after assignment.
 * The Java {@link Process} returned to the lifecycle retains the native
 * process and pipe handles; no later PID adoption or descendant polling is
 * used as the ownership boundary.</p>
 *
 * <p>This class intentionally exposes only the existing narrow supervisor
 * contract. It is Windows-only and requires Java native access to be enabled
 * for the hosting JVM.</p>
 *
 * @since 1.0
 */
@SuppressWarnings("restricted")
public final class WindowsJobObjectProcessTreeSupervisor implements ManagedProcessTreeSupervisor, AutoCloseable {

    /**
     * CREATE_SUSPENDED.
     */
    static final int CREATE_SUSPENDED = 0x00000004;
    /**
     * CREATE_UNICODE_ENVIRONMENT.
     */
    static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    /**
     * STARTF_USESTDHANDLES.
     */
    static final int STARTF_USESTDHANDLES = 0x00000100;
    /**
     * HANDLE_FLAG_INHERIT.
     */
    static final int HANDLE_FLAG_INHERIT = 0x00000001;
    /**
     * JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.
     */
    static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    /**
     * JobObjectExtendedLimitInformation.
     */
    static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    /**
     * JobObjectBasicAccountingInformation.
     */
    static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION = 1;
    /**
     * WAIT_OBJECT_0.
     */
    static final int WAIT_OBJECT_0 = 0;
    /**
     * WAIT_TIMEOUT.
     */
    static final int WAIT_TIMEOUT = 0x00000102;
    /**
     * INFINITE.
     */
    static final int INFINITE = 0xFFFFFFFF;
    /**
     * x64 STARTUPINFO size.
     */
    static final long STARTUPINFO_SIZE = 104;
    /**
     * x64 PROCESS_INFORMATION size.
     */
    static final long PROCESS_INFORMATION_SIZE = 24;
    /**
     * x64 JOBOBJECT_EXTENDED_LIMIT_INFORMATION size.
     */
    static final long JOB_LIMITS_SIZE = 144;
    /**
     * x64 JOBOBJECT_BASIC_ACCOUNTING_INFORMATION size.
     */
    static final long JOB_ACCOUNTING_SIZE = 48;
    private final NativeApi api;
    private final Map<WindowsManagedProcess, Job> jobs = new ConcurrentHashMap<>();
    /**
     * Creates the Windows supervisor.
     *
     * @throws IOException when the current platform is not Windows or the
     *                     native kernel API cannot be loaded
     */
    public WindowsJobObjectProcessTreeSupervisor() throws IOException {
        if (!isWindows()) {
            throw new IOException("managed_windows_job_objects_unsupported");
        }
        this.api = new NativeApi();
    }

    /**
     * Checks whether this JVM is running on Windows.
     *
     * @return true when the current operating system is Windows
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static String environmentBlock(Map<String, String> values) {
        return values.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\0", "", "\0\0"));
    }

    private static MemorySegment utf16(Arena arena, String value) {
        return arena.allocateFrom(JAVA_CHAR, (value + "\0").toCharArray());
    }

    private static String windowsCommandLine(List<String> command) {
        return command.stream()
                .map(WindowsJobObjectProcessTreeSupervisor::quoteWindowsArgument)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quoteWindowsArgument(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0 && !value.isEmpty()) {
            return value;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int slashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\') {
                slashes++;
            } else if (character == '"') {
                quoted.append("\\".repeat(slashes * 2 + 1))
                        .append('"');
                slashes = 0;
            } else {
                quoted.append("\\".repeat(slashes))
                        .append(character);
                slashes = 0;
            }
        }
        quoted.append("\\".repeat(slashes * 2))
                .append('"');
        return quoted.toString();
    }

    private static void requireHandle(MemorySegment handle) throws IOException {
        if (handle == null || handle.address() == 0) {
            throw new IOException("windows_handle_invalid");
        }
    }

    /**
     * Launches one new root in one new Job before resuming it.
     *
     * @param command          executable argv
     * @param workingDirectory process working directory
     * @param environment      complete child environment
     * @return contained process
     * @throws IOException when native launch or Job assignment fails
     */
    @Override
    public Process launch(List<String> command, Path workingDirectory, Map<String, String> environment)
            throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        if (command.isEmpty() || command.stream()
                .anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("command is empty or contains null");
        }
        Job job = null;
        Handles handles = null;
        try (Arena arena = Arena.ofConfined()) {
            job = createJob(arena);
            handles = createPipes(arena);
            MemorySegment startup = arena.allocate(STARTUPINFO_SIZE, 8);
            startup.set(JAVA_INT, 0, (int) STARTUPINFO_SIZE);
            startup.set(JAVA_INT, 60, STARTF_USESTDHANDLES);
            startup.set(ADDRESS, 80, handles.childStdInput());
            startup.set(ADDRESS, 88, handles.childStdOutput());
            startup.set(ADDRESS, 96, handles.childStdError());
            MemorySegment processInfo = arena.allocate(PROCESS_INFORMATION_SIZE, 8);
            MemorySegment commandLine = utf16(arena, windowsCommandLine(command));
            MemorySegment environmentBlock = utf16(arena, environmentBlock(environment));
            MemorySegment directory = utf16(arena,
                    workingDirectory.toAbsolutePath()
                            .normalize()
                            .toString());
            int created = api.createProcess(commandLine, CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT,
                    environmentBlock, directory, startup, processInfo);
            if (created == 0) {
                throw api.failure("CreateProcessW");
            }
            MemorySegment processHandle = processInfo.get(ADDRESS, 0);
            MemorySegment threadHandle = processInfo.get(ADDRESS, 8);
            long processId = Integer.toUnsignedLong(processInfo.get(JAVA_INT, 16));
            try {
                if (api.assignProcessToJob(job.handle(), processHandle) == 0) {
                    throw api.failure("AssignProcessToJobObject");
                }
                int resumed = api.resumeThread(threadHandle);
                if (resumed == -1) {
                    throw api.failure("ResumeThread");
                }
                job.markRunning();
            } catch (IOException failure) {
                api.terminateJob(job.handle());
                api.closeHandle(threadHandle);
                api.closeHandle(processHandle);
                throw failure;
            } finally {
                api.closeHandle(threadHandle);
            }
            handles.closeChildEnds(api);
            WindowsManagedProcess process = new WindowsManagedProcess(api, processHandle, processId,
                    handles.parentStdInput(), handles.parentStdOutput(), handles.parentStdError());
            jobs.put(process, job.withProcess(process));
            return process;
        } catch (Throwable failure) {
            if (handles != null) {
                handles.closeAll(api);
            }
            if (job != null) {
                job.close(api);
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            throw new IOException("managed_windows_job_launch_failed", failure);
        }
    }

    /**
     * Verifies both native process identity and Job membership.
     *
     * @param process process returned by {@link #launch(List, Path, Map)}
     * @return true only for the exact retained process and Job
     */
    @Override
    public boolean owns(Process process) {
        if (!(process instanceof WindowsManagedProcess managed)) {
            return false;
        }
        Job job = jobs.get(managed);
        if (job == null || job.process() != managed || managed.closed()) {
            return false;
        }
        try {
            return api.processId(managed.processHandle()) == managed.pid()
                    && api.isProcessInJob(managed.processHandle(), job.handle());
        } catch (IOException failure) {
            return false;
        }
    }

    /**
     * Returns the native lifecycle state for a process retained by this
     * supervisor.
     *
     * @param process process returned from {@link #launch(List, Path, Map)}
     * @return current state, or {@link State#AMBIGUOUS} when not owned
     */
    public State state(Process process) {
        if (!(process instanceof WindowsManagedProcess managed)) {
            return State.AMBIGUOUS;
        }
        Job job = jobs.get(managed);
        return job == null ? managed.closed() ? State.DEAD : State.AMBIGUOUS : job.state();
    }

    /**
     * Terminates the complete Job and proves root termination plus zero active
     * Job processes before releasing all native handles.
     *
     * @param process contained root process
     * @return true only when the Job is definitively empty
     * @throws IOException when the Job cannot be proven empty
     */
    @Override
    public boolean teardownAndProveEmpty(Process process) throws IOException {
        if (!(process instanceof WindowsManagedProcess managed)) {
            throw new IOException("managed_process_not_owned");
        }
        Job job = jobs.get(managed);
        if (job == null || !owns(managed)) {
            throw new IOException("managed_process_tree_ownership_unproven");
        }
        if (job.state() == State.AMBIGUOUS) {
            throw new IOException("managed_process_tree_death_ambiguous");
        }
        job.markTearingDown();
        if (!job.teardownIssued()) {
            job.markTeardownIssued();
            if (api.terminateJob(job.handle()) == 0 && api.lastError() != 0) {
                throw api.failure("TerminateJobObject");
            }
        }
        int rootWait = api.waitForSingleObject(managed.processHandle(), 10_000);
        if (rootWait == WAIT_TIMEOUT) {
            job.markAmbiguous();
            throw new IOException("managed_process_tree_ambiguous_root_liveness");
        }
        if (rootWait != WAIT_OBJECT_0) {
            job.markAmbiguous();
            throw api.failure("WaitForSingleObject(process)");
        }
        int jobWait = api.waitForSingleObject(job.handle(), 10_000);
        if (jobWait == WAIT_TIMEOUT) {
            job.markAmbiguous();
            throw new IOException("managed_process_tree_ambiguous_job_liveness");
        }
        if (jobWait != WAIT_OBJECT_0) {
            job.markAmbiguous();
            throw api.failure("WaitForSingleObject(job)");
        }
        if (api.activeProcesses(job.handle()) != 0) {
            job.markAmbiguous();
            throw new IOException("managed_process_tree_not_empty");
        }
        job.markDead();
        jobs.remove(managed, job);
        job.close(api);
        managed.closeNativeHandles();
        return true;
    }

    /**
     * Releases any still-owned Job after best-effort teardown.
     */
    @Override
    public void close() {
        for (WindowsManagedProcess process : new ArrayList<>(jobs.keySet())) {
            try {
                teardownAndProveEmpty(process);
            } catch (IOException ignored) {
                // A failed proof remains fail-closed; do not claim cleanup.
            }
        }
    }

    private Job createJob(Arena arena) throws IOException {
        MemorySegment jobHandle = api.createJob(arena);
        if (jobHandle == null || jobHandle.address() == 0) {
            throw api.failure("CreateJobObjectW");
        }
        if (api.setHandleInherit(jobHandle, false) == 0) {
            api.closeHandle(jobHandle);
            throw api.failure("SetHandleInformation(job)");
        }
        MemorySegment limits = arena.allocate(JOB_LIMITS_SIZE, 8);
        limits.set(JAVA_INT, 16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
        if (api.setJobLimits(jobHandle, limits) == 0) {
            api.closeHandle(jobHandle);
            throw api.failure("SetInformationJobObject");
        }
        return new Job(jobHandle, null);
    }

    private Handles createPipes(Arena arena) throws IOException {
        MemorySegment security = arena.allocate(24, 8);
        security.set(JAVA_INT, 0, 24);
        security.set(ADDRESS, 8, MemorySegment.NULL);
        security.set(JAVA_INT, 16, 1);
        MemorySegment inRead = arena.allocate(ADDRESS);
        MemorySegment inWrite = arena.allocate(ADDRESS);
        MemorySegment outRead = arena.allocate(ADDRESS);
        MemorySegment outWrite = arena.allocate(ADDRESS);
        MemorySegment errRead = arena.allocate(ADDRESS);
        MemorySegment errWrite = arena.allocate(ADDRESS);
        if (api.createPipe(inRead, inWrite, security) == 0 || api.createPipe(outRead, outWrite, security) == 0
                || api.createPipe(errRead, errWrite, security) == 0) {
            throw api.failure("CreatePipe");
        }
        MemorySegment parentIn = inWrite.get(ADDRESS, 0);
        MemorySegment parentOut = outRead.get(ADDRESS, 0);
        MemorySegment parentErr = errRead.get(ADDRESS, 0);
        if (api.setHandleInherit(parentIn, false) == 0 || api.setHandleInherit(parentOut, false) == 0
                || api.setHandleInherit(parentErr, false) == 0) {
            throw api.failure("SetHandleInformation(pipe)");
        }
        return new Handles(inRead.get(ADDRESS, 0), outWrite.get(ADDRESS, 0), errWrite.get(ADDRESS, 0), parentIn,
                parentOut, parentErr);
    }

    /**
     * Minimal native process-tree lifecycle classification.
     */
    public enum State {
        /**
         * Job exists but its root has not resumed.
         */
        CREATED,
        /**
         * Root and descendants may execute inside the Job.
         */
        RUNNING,
        /**
         * Job teardown has been requested and is being proven.
         */
        TEARING_DOWN,
        /**
         * Root and Job are definitively dead and empty.
         */
        DEAD,
        /**
         * Native ownership or liveness could not be proven.
         */
        AMBIGUOUS
    }

    private record Handles(MemorySegment childStdInput, MemorySegment childStdOutput, MemorySegment childStdError,
                           MemorySegment parentStdInput, MemorySegment parentStdOutput, MemorySegment parentStdError) {

        private void closeChildEnds(NativeApi api) {
            api.closeHandle(childStdInput);
            api.closeHandle(childStdOutput);
            api.closeHandle(childStdError);
        }

        private void closeAll(NativeApi api) {
            closeChildEnds(api);
            api.closeHandle(parentStdInput);
            api.closeHandle(parentStdOutput);
            api.closeHandle(parentStdError);
        }
    }

    private static final class Job {

        private final MemorySegment handle;
        private final WindowsManagedProcess process;
        private boolean teardownIssued;
        private volatile State state = State.CREATED;

        private Job(MemorySegment handle, WindowsManagedProcess process) {
            this.handle = handle;
            this.process = process;
        }

        private Job withProcess(WindowsManagedProcess value) {
            Job replacement = new Job(handle, value);
            replacement.state = state;
            replacement.teardownIssued = teardownIssued;
            return replacement;
        }

        private MemorySegment handle() {
            return handle;
        }

        private WindowsManagedProcess process() {
            return process;
        }

        private boolean teardownIssued() {
            return teardownIssued;
        }

        private State state() {
            return state;
        }

        private void markRunning() {
            state = State.RUNNING;
        }

        private void markTearingDown() {
            state = State.TEARING_DOWN;
        }

        private void markAmbiguous() {
            state = State.AMBIGUOUS;
        }

        private void markDead() {
            state = State.DEAD;
        }

        private void markTeardownIssued() {
            teardownIssued = true;
        }

        private void close(NativeApi api) {
            api.closeHandle(handle);
        }
    }

    private static final class WindowsManagedProcess extends Process {

        private final NativeApi api;
        private final MemorySegment processHandle;
        private final long pid;
        private final NativePipeInputStream stdout;
        private final NativePipeOutputStream stdin;
        private final NativePipeInputStream stderr;
        private volatile boolean closed;

        private WindowsManagedProcess(NativeApi api, MemorySegment processHandle, long pid, MemorySegment stdin,
                MemorySegment stdout, MemorySegment stderr) {
            this.api = api;
            this.processHandle = processHandle;
            this.pid = pid;
            this.stdin = new NativePipeOutputStream(api, stdin);
            this.stdout = new NativePipeInputStream(api, stdout);
            this.stderr = new NativePipeInputStream(api, stderr);
        }

        private MemorySegment processHandle() {
            return processHandle;
        }

        private boolean closed() {
            return closed;
        }

        private void closeNativeHandles() {
            if (!closed) {
                closed = true;
                stdin.closeQuietly();
                stdout.closeQuietly();
                stderr.closeQuietly();
                api.closeHandle(processHandle);
            }
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
            int result = api.waitForSingleObject(processHandle, INFINITE);
            if (result != WAIT_OBJECT_0) {
                throw new InterruptedException("WaitForSingleObject failed");
            }
            return exitValue();
        }

        @Override
        public int exitValue() {
            try {
                if (api.waitForSingleObject(processHandle, 0) == WAIT_TIMEOUT) {
                    throw new IllegalThreadStateException("process is still running");
                }
                return api.exitCode(processHandle);
            } catch (IOException failure) {
                throw new IllegalThreadStateException(failure.getMessage());
            }
        }

        @Override
        public void destroy() {
            try {
                api.terminateProcess(processHandle);
            } catch (IOException ignored) {
                // Process.destroy is best effort; supervisor teardown is authoritative.
            }
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

        @Override
        public boolean isAlive() {
            try {
                return api.waitForSingleObject(processHandle, 0) == WAIT_TIMEOUT;
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override
        public long pid() {
            return pid;
        }
    }

    private static final class NativePipeInputStream extends InputStream {

        private final NativeApi api;
        private final MemorySegment handle;
        private volatile boolean closed;

        private NativePipeInputStream(NativeApi api, MemorySegment handle) {
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
                int result = api.readFile(handle, buffer, length, read);
                if (result == 0) {
                    if (api.lastError() == 109) { // ERROR_BROKEN_PIPE
                        return -1;
                    }
                    throw api.failure("ReadFile");
                }
                int count = read.get(JAVA_INT, 0);
                MemorySegment.copy(buffer, JAVA_BYTE, 0, bytes, offset, count);
                return count;
            }
        }

        @Override
        public void close() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (!closed) {
                closed = true;
                api.closeHandle(handle);
            }
        }
    }

    private static final class NativePipeOutputStream extends OutputStream {

        private final NativeApi api;
        private final MemorySegment handle;
        private volatile boolean closed;

        private NativePipeOutputStream(NativeApi api, MemorySegment handle) {
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
                throw new IOException("pipe_closed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(length, 1);
                MemorySegment.copy(bytes, offset, buffer, JAVA_BYTE, 0, length);
                MemorySegment written = arena.allocate(JAVA_INT);
                if (api.writeFile(handle, buffer, length, written) == 0
                        || written.get(JAVA_INT, 0) != length) {
                    throw api.failure("WriteFile");
                }
            }
        }

        @Override
        public void close() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (!closed) {
                closed = true;
                api.closeHandle(handle);
            }
        }
    }

    private static final class NativeApi {

        private final Linker linker = Linker.nativeLinker();
        private final SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private final MethodHandle createJobObject;
        private final MethodHandle setInformationJobObject;
        private final MethodHandle setHandleInformation;
        private final MethodHandle createPipe;
        private final MethodHandle createProcess;
        private final MethodHandle assignProcessToJobObject;
        private final MethodHandle resumeThread;
        private final MethodHandle terminateJobObject;
        private final MethodHandle terminateProcess;
        private final MethodHandle waitForSingleObject;
        private final MethodHandle queryInformationJobObject;
        private final MethodHandle isProcessInJob;
        private final MethodHandle getProcessId;
        private final MethodHandle getExitCodeProcess;
        private final MethodHandle closeHandle;
        private final MethodHandle getLastError;

        private NativeApi() throws IOException {
            try {
                createJobObject = bind("CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
                setInformationJobObject = bind("SetInformationJobObject",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
                setHandleInformation = bind("SetHandleInformation",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
                createPipe = bind("CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                        JAVA_INT));
                createProcess = bind("CreateProcessW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                        ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
                assignProcessToJobObject = bind("AssignProcessToJobObject",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                resumeThread = bind("ResumeThread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                terminateJobObject = bind("TerminateJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                terminateProcess = bind("TerminateProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                waitForSingleObject = bind("WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                queryInformationJobObject = bind("QueryInformationJobObject",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
                isProcessInJob = bind("IsProcessInJob", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                getProcessId = bind("GetProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                getExitCodeProcess = bind("GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                closeHandle = bind("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                getLastError = bind("GetLastError", FunctionDescriptor.of(JAVA_INT));
            } catch (RuntimeException failure) {
                throw new IOException("windows_kernel32_binding_failed", failure);
            }
        }

        private MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(kernel32.find(name)
                    .orElseThrow(), descriptor);
        }

        private MemorySegment createJob(Arena arena) throws IOException {
            return (MemorySegment) invoke(createJobObject, MemorySegment.NULL, MemorySegment.NULL);
        }

        private int setJobLimits(MemorySegment job, MemorySegment limits) throws IOException {
            return (int) invoke(setInformationJobObject, job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits,
                    (int) JOB_LIMITS_SIZE);
        }

        private int setHandleInherit(MemorySegment handle, boolean inherit) throws IOException {
            return (int) invoke(setHandleInformation, handle, HANDLE_FLAG_INHERIT, inherit ? HANDLE_FLAG_INHERIT : 0);
        }

        private int createPipe(MemorySegment read, MemorySegment write, MemorySegment security) throws IOException {
            return (int) invoke(createPipe, read, write, security, 0);
        }

        private int createProcess(MemorySegment commandLine, int flags, MemorySegment environment,
                MemorySegment directory, MemorySegment startup, MemorySegment processInfo) throws IOException {
            return (int) invoke(createProcess, MemorySegment.NULL, commandLine, MemorySegment.NULL, MemorySegment.NULL,
                    1, flags, environment, directory, startup, processInfo);
        }

        private int assignProcessToJob(MemorySegment job, MemorySegment process) throws IOException {
            return (int) invoke(assignProcessToJobObject, job, process);
        }

        private int resumeThread(MemorySegment thread) throws IOException {
            return (int) invoke(resumeThread, thread);
        }

        private int terminateJob(MemorySegment job) throws IOException {
            return (int) invoke(terminateJobObject, job, 1);
        }

        private int terminateProcess(MemorySegment process) throws IOException {
            return (int) invoke(terminateProcess, process, 1);
        }

        private int waitForSingleObject(MemorySegment handle, int timeout) {
            try {
                return (int) invoke(waitForSingleObject, handle, timeout);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private int activeProcesses(MemorySegment job) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment accounting = arena.allocate(JOB_ACCOUNTING_SIZE, 8);
                MemorySegment returned = arena.allocate(JAVA_INT);
                int result = (int) invoke(queryInformationJobObject, job, JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION,
                        accounting, (int) JOB_ACCOUNTING_SIZE, returned);
                if (result == 0) {
                    throw failure("QueryInformationJobObject");
                }
                return accounting.get(JAVA_INT, 40);
            }
        }

        private boolean isProcessInJob(MemorySegment process, MemorySegment job) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = arena.allocate(JAVA_INT);
                if ((int) invoke(isProcessInJob, process, job, result) == 0) {
                    throw failure("IsProcessInJob");
                }
                return result.get(JAVA_INT, 0) != 0;
            }
        }

        private long processId(MemorySegment process) throws IOException {
            return Integer.toUnsignedLong((int) invoke(getProcessId, process));
        }

        private int exitCode(MemorySegment process) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = arena.allocate(JAVA_INT);
                if ((int) invoke(getExitCodeProcess, process, result) == 0) {
                    throw failure("GetExitCodeProcess");
                }
                return result.get(JAVA_INT, 0);
            }
        }

        private int readFile(MemorySegment handle, MemorySegment buffer, int length, MemorySegment read)
                throws IOException {
            return (int) invoke(readFileHandle(), handle, buffer, length, read, MemorySegment.NULL);
        }

        private int writeFile(MemorySegment handle, MemorySegment buffer, int length, MemorySegment written)
                throws IOException {
            return (int) invoke(writeFileHandle(), handle, buffer, length, written, MemorySegment.NULL);
        }

        private MethodHandle readFileHandle() {
            return bind("ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        }

        private MethodHandle writeFileHandle() {
            return bind("WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        }

        private void closeHandle(MemorySegment handle) {
            if (handle != null && handle.address() != 0) {
                try {
                    invoke(closeHandle, handle);
                } catch (IOException ignored) {
                    // Cleanup is best effort; liveness proof is performed before close.
                }
            }
        }

        private int lastError() {
            try {
                return (int) invoke(getLastError);
            } catch (IOException failure) {
                return -1;
            }
        }

        private IOException failure(String operation) {
            return new IOException(operation + " failed, win32=" + lastError());
        }

        private Object invoke(MethodHandle handle, Object... arguments) throws IOException {
            try {
                return handle.invokeWithArguments(arguments);
            } catch (Throwable failure) {
                throw new IOException("windows_native_call_failed", failure);
            }
        }
    }
}
