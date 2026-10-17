package org.synesis.workspace.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A command that only sleeps is how agents waited instead of ending their turn (ADR-0099): every one below that is
 * refused was seen in a real run or is the same wait in another shell; every one allowed does real work.
 */
class SleepCommandsTest {

    @Test
    void theSleepsAgentsRanThroughRunCommandAreSleeps() {
        assertTrue(SleepCommands.onlySleeps(List.of("python", "-c", "import time; time.sleep(590)")));
        assertTrue(SleepCommands.onlySleeps(List.of("python3", "-c", "import time\ntime.sleep(20)")));
        assertTrue(SleepCommands.onlySleeps(List.of("py", "-c", "from time import sleep; sleep(1.5)")));
        assertTrue(SleepCommands.onlySleeps(List.of("sleep", "240")));
        assertTrue(SleepCommands.onlySleeps(List.of("/usr/bin/sleep", "1m", "30s")));
        assertTrue(SleepCommands.onlySleeps(List.of("timeout", "/t", "60", "/nobreak")));
        assertTrue(SleepCommands.onlySleeps(List.of("ping", "-n", "30", "127.0.0.1")));
        assertTrue(SleepCommands.onlySleeps(List.of("node", "-e", "setTimeout(() => {}, 5000)")));
    }

    @Test
    void aSleepBehindAShellIsStillASleep() {
        assertTrue(SleepCommands.onlySleeps(List.of("bash", "-c", "sleep 1200")));
        assertTrue(SleepCommands.onlySleeps(List.of("cmd.exe", "/d", "/s", "/c", "timeout /t 60 > nul")));
        assertTrue(SleepCommands.onlySleeps(List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30")));
        assertTrue(SleepCommands.onlySleeps(List.of("pwsh", "-c", "Start-Sleep 5")));
    }

    @Test
    void aShellToolCommandLineIsReadTheSameWay() {
        assertTrue(SleepCommands.onlySleeps("sleep 240"));
        assertTrue(SleepCommands.onlySleeps("sleep 60 && sleep 60"));
        assertTrue(SleepCommands.onlySleeps("python -c \"import time; time.sleep(590)\""));
        assertTrue(SleepCommands.onlySleeps("Start-Sleep -Seconds 300"));
        assertTrue(SleepCommands.onlySleeps("sleep 30 >/dev/null 2>&1"));
        // Saying something after the wait is still the wait.
        assertTrue(SleepCommands.onlySleeps("sleep 600 && echo done"));
        assertTrue(SleepCommands.onlySleeps(List.of("python", "-c", "import time; time.sleep(590); print('done')")));
    }

    @Test
    void aCommandThatDoesWorkIsNotASleep() {
        assertFalse(SleepCommands.onlySleeps(List.of("python", "-m", "unittest", "-v")));
        assertFalse(SleepCommands.onlySleeps(List.of("python", "-c", "import time; time.sleep(1); print(open('a').read())")));
        assertFalse(SleepCommands.onlySleeps(List.of("sleep")));
        assertFalse(SleepCommands.onlySleeps(List.of("timeout", "5", "python", "-m", "unittest")));
        assertFalse(SleepCommands.onlySleeps(List.of("ping", "-n", "3", "example.com")));
        assertFalse(SleepCommands.onlySleeps(List.of("git", "status")));
        assertFalse(SleepCommands.onlySleeps(List.of()));
        assertFalse(SleepCommands.onlySleeps("sleep 5 && python -m unittest"));
        assertFalse(SleepCommands.onlySleeps("ls -la"));
        assertFalse(SleepCommands.onlySleeps("echo done"));
        assertFalse(SleepCommands.onlySleeps(""));
    }
}
