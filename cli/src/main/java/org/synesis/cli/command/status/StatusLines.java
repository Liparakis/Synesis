package org.synesis.cli.command.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.synesis.workspace.transport.control.ProjectStatus;

/**
 * Turns a {@link ProjectStatus} into lines: the one screen a person reads, or the stable {@code STATUS_*} keys.
 *
 * <p>Agents, lanes and operator requests are text that agents wrote. Both forms print it on one line, without control
 * or formatting characters, so a lane's goal cannot add a line that reads as another record or drive the terminal.</p>
 */
final class StatusLines {

    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\p{Cf}\\u2028\\u2029\\s]+");
    private static final int SHORT_ID = 12;
    private static final int WIDTH = 100;
    private static final String LABEL = "%-11s";
    /** Detail lines stand under the value of their line, and what leads on from a detail stands two further in. */
    private static final String DETAIL = " ".repeat(13);
    private static final String DEEPER = DETAIL + "  ";

    private StatusLines() {
    }

    /**
     * The screen: a line each for the project, the runtime, the agents, what needs a person and the computers, with
     * the detail under it and the command that goes deeper.
     *
     * @param status the status
     * @param now    the time "ago" is counted from
     * @return the lines
     */
    static List<String> screen(ProjectStatus status, Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(LABEL, "Project") + status.name() + "  (" + (status.shared() ? "shared with other computers" + owner(status) : "on this computer only") + ")");
        lines.add(String.format(LABEL, "Runtime") + runtime(status));
        long working = status.agents().stream().filter(agent -> agent.state().equals("ACTIVE")).count();
        lines.add(String.format(LABEL, "Agents") + (status.agents().isEmpty() ? "none yet" : status.agents().size() + " (" + working + " working)"
                + (status.agents().size() > working ? ", the others finished, stopped or waiting to be resumed" : "")));
        for (ProjectStatus.Agent agent : status.agents()) {
            lines.add(DETAIL + agent.provider() + " " + shorten(agent.id()) + "  " + word(agent.state()) + (agent.role().isEmpty() ? "" : " as " + oneLine(agent.role())) + ": " + oneLine(agent.lane()));
            if (!agent.files().isEmpty()) {
                lines.add(DEEPER + "files: " + oneLine(String.join(", ", agent.files())));
            }
            if (!agent.waitingOn().isEmpty()) {
                lines.add(DEEPER + "waiting on: " + oneLine(String.join(", ", agent.waitingOn())));
            }
        }
        long operator = status.needs().stream().filter(need -> need.kind().equals("operator")).count();
        long joins = status.needs().size() - operator;
        lines.add(String.format(LABEL, "Needs you") + (status.needs().isEmpty() ? "nothing" : operator + " operator request(s), " + joins + " computer(s) asking to join"));
        for (ProjectStatus.Need need : status.needs()) {
            lines.add(DETAIL + (need.kind().equals("operator") ? "operator request " : "join request from ") + shorten(need.id()) + ": " + oneLine(need.summary()));
        }
        if (operator > 0) {
            lines.add(DEEPER + "see them: synesis operator list");
        }
        if (joins > 0) {
            lines.add(DEEPER + "answer them: synesis link requests");
        }
        lines.add(String.format(LABEL, "Computers") + (status.computers().isEmpty() ? (status.shared() ? "no other computer yet" : "this one only") : status.computers().size() + " other(s)"));
        for (ProjectStatus.Computer computer : status.computers()) {
            lines.add(DETAIL + shorten(computer.node()) + "  " + connection(computer, now) + "; " + received(computer, now));
        }
        return lines;
    }

    /**
     * The same facts as {@code STATUS_*} keys, one record to a line.
     *
     * @param status the status
     * @return the lines
     */
    static List<String> porcelain(ProjectStatus status) {
        List<String> lines = new ArrayList<>();
        lines.add("STATUS_PROJECT_ID=" + status.projectId());
        lines.add("STATUS_PROJECT_NAME=" + flat(status.name()));
        lines.add("STATUS_PROJECT_SHARING=" + (status.shared() ? "SHARED" : "SINGLE_COMPUTER"));
        lines.add("STATUS_OWNER=" + (status.owner().isEmpty() ? "NONE" : status.owner()));
        lines.add("STATUS_THIS_COMPUTER=" + (status.self().isEmpty() ? "UNKNOWN" : status.self()));
        lines.add("STATUS_RUNTIME=" + (status.runtimeRunning() ? "RUNNING" : "STOPPED"));
        lines.add("STATUS_AGENTS=" + status.agents().size());
        for (ProjectStatus.Agent agent : status.agents()) {
            lines.add("STATUS_AGENT=" + agent.id() + " PROVIDER=" + agent.provider() + " STATE=" + agent.state() + " ROLE=" + (agent.role().isEmpty() ? "NONE" : flat(agent.role()))
                    + " FILES=" + flat(String.join(",", agent.files())) + " WAITING_ON=" + flat(String.join(",", agent.waitingOn())) + " LANE=" + flat(agent.lane()));
        }
        lines.add("STATUS_NEEDS=" + status.needs().size());
        for (ProjectStatus.Need need : status.needs()) {
            lines.add("STATUS_NEED=" + need.kind().toUpperCase(java.util.Locale.ROOT) + " ID=" + need.id() + " SUMMARY=" + flat(need.summary()));
        }
        lines.add("STATUS_COMPUTERS=" + status.computers().size());
        for (ProjectStatus.Computer computer : status.computers()) {
            lines.add("STATUS_COMPUTER=" + computer.node() + " STATE=" + computer.state() + " SINCE=" + computer.since().map(Instant::toString).orElse("NONE")
                    + " LAST_ENTRY_RECEIVED=" + computer.lastReceived().map(Instant::toString).orElse("NONE"));
        }
        return lines;
    }

    private static String owner(ProjectStatus status) {
        if (status.owner().isEmpty()) {
            return "";
        }
        return ", owner: " + (status.owner().equals(status.self()) ? "this computer" : shorten(status.owner()));
    }

    private static String runtime(ProjectStatus status) {
        if (status.runtimeRunning()) {
            return "running";
        }
        return status.shared() ? "not running; other computers reach this one only while it runs (synesis ui --no-browser starts it)"
                : "not running; a project on one computer does not need it";
    }

    private static String word(String state) {
        return switch (state) {
            case "ACTIVE" -> "working";
            case "SUSPENDED", "RECOVERY_HELD" -> "waiting to be resumed";
            case "COMPLETED" -> "finished";
            case "CANCELLED", "REVOKED" -> "stopped";
            default -> state.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String connection(ProjectStatus.Computer computer, Instant now) {
        return switch (computer.state()) {
            case "CONNECTED" -> "connected" + computer.since().map(since -> " for " + span(since, now)).orElse("");
            case "DISCONNECTED" -> "not connected" + computer.since().map(since -> " for " + span(since, now)).orElse("");
            default -> "connection unknown, the runtime is not running";
        };
    }

    private static String received(ProjectStatus.Computer computer, Instant now) {
        Optional<Instant> last = computer.lastReceived();
        return last.map(time -> "last entries from it arrived " + span(time, now) + " ago").orElse("no entries from it yet");
    }

    private static String span(Instant from, Instant to) {
        Duration elapsed = Duration.between(from, to);
        if (elapsed.isNegative() || elapsed.toSeconds() < 60) {
            return Math.max(0, elapsed.toSeconds()) + " s";
        }
        if (elapsed.toMinutes() < 60) {
            return elapsed.toMinutes() + " min";
        }
        return elapsed.toHours() < 48 ? elapsed.toHours() + " h" : elapsed.toDays() + " d";
    }

    private static String shorten(String id) {
        return id.length() > SHORT_ID + 1 ? id.substring(0, SHORT_ID) + "..." : id;
    }

    /** Text an agent wrote, on one line and cut to the screen's width. */
    private static String oneLine(String text) {
        String line = flat(text);
        return line.length() <= WIDTH ? line : line.substring(0, WIDTH) + "...";
    }

    /** Text an agent wrote, on one line. */
    private static String flat(String text) {
        return UNSAFE.matcher(text).replaceAll(" ").strip();
    }
}
