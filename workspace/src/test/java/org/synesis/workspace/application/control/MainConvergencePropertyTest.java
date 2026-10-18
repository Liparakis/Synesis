package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.application.control.MainMeeting.Relation;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * ADR-0098 step 5, fitness functions "main moves only forward" and "copies converge": random own advances, cuts and
 * meetings among three copies of {@code main} end with every copy equal, and no copy ever moved to a commit that is not
 * a descendant of its previous head. The model is the copies only (one Git repository, one detached worktree, a ref per
 * copy), applying {@link MainMeeting#relate} and the production pass: a meeting exchanges what each knows of every
 * writer's last advance (the later one wins), then each side fast-forwards to every known head it is behind, and
 * re-merges (RemergeService.run) only when it is behind none, the diverged heads in the way have no higher writer, onto
 * the lowest in-the-way head, with one {@code merge --no-ff} per own advance the other lacks and then its own old head.
 */
class MainConvergencePropertyTest {

    private static final int SEEDS = 20;
    private static final int STEPS = 30;
    private static final int MAX_ROUNDS = 10;
    private static final List<String> NODES = List.of("node-a", "node-b", "node-c");

    @TempDir
    Path temp;

    /** The final phase meets the pairs in the fixed order (a,b), (a,c), (b,c) every round. */
    @Test
    void randomMergesCutsAndMeetingsConvergeAndOnlyMoveForward() throws Exception {
        runSeeds(false);
    }

    /** The final phase meets the pairs in a seeded random order each round. */
    @Test
    void randomMergesCutsAndMeetingsConvergeWhenFinalMeetingsComeInRandomOrder() throws Exception {
        runSeeds(true);
    }

    private void runSeeds(boolean shuffled) {
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        IntStream.rangeClosed(1, SEEDS).parallel().forEach(seed -> {
            try {
                new Run(seed, temp.resolve((shuffled ? "shuffled-" : "fixed-") + seed), shuffled).execute();
            } catch (AssertionError | IOException | RuntimeException failure) {
                failures.add("seed " + seed + ": " + failure);
            }
        });
        assertTrue(failures.isEmpty(), "failing seeds: " + failures);
    }

    private static final class Run {
        private final int seed;
        private final Random random;
        private final Path repo;
        private final Map<String, String> heads = new TreeMap<>();
        private final Map<String, List<String>> advances = new TreeMap<>();
        private final List<String> ownAdvanceCommits = new ArrayList<>();
        private final List<String> trace = new ArrayList<>();
        private final java.util.Set<String> cut = new java.util.HashSet<>();
        private final Map<String, Map<String, String>> known = new TreeMap<>();
        private final boolean shuffled;
        private int counter;

        Run(int seed, Path repo, boolean shuffled) {
            this.shuffled = shuffled;
            this.seed = seed;
            this.random = new Random(seed);
            this.repo = repo;
        }

        void execute() throws IOException {
            Files.createDirectories(repo);
            GitProcessRunner.run(repo, "init");
            Files.writeString(repo.resolve("root.txt"), "root\n");
            GitProcessRunner.run(repo, "add", "root.txt");
            GitProcessRunner.run(repo, "commit", "-m", "root");
            String root = GitProcessRunner.run(repo, "rev-parse", "HEAD");
            for (String node : NODES) {
                heads.put(node, root);
                advances.put(node, new ArrayList<>());
                known.put(node, new TreeMap<>());
            }
            for (int step = 0; step < STEPS; step++) {
                switch (random.nextInt(4)) {
                    case 0 -> advance(pick());
                    case 1 -> toggleCut();
                    default -> meet();
                }
            }
            cut.clear();
            int rounds = 0;
            boolean changed = true;
            while (changed) {
                assertTrue(++rounds <= MAX_ROUNDS, fail("not quiescent within " + MAX_ROUNDS + " rounds"));
                changed = false;
                List<String[]> pairs = new ArrayList<>(List.of(new String[] {"node-a", "node-b"},
                        new String[] {"node-a", "node-c"}, new String[] {"node-b", "node-c"}));
                if (shuffled) {
                    java.util.Collections.shuffle(pairs, random);
                }
                for (String[] pair : pairs) {
                    changed |= meeting(pair[0], pair[1]);
                }
            }
            String finalHead = heads.get("node-a");
            for (String node : NODES) {
                assertTrue(finalHead.equals(heads.get(node)), fail(node + " ends at " + heads.get(node) + ", not " + finalHead));
            }
            for (String commit : ownAdvanceCommits) {
                assertTrue(GitProcessRunner.isAncestor(repo, commit, finalHead), fail("own advance " + commit + " lost"));
            }
        }

        private String fail(String why) {
            return "seed " + seed + ": " + why + "\ntrace=" + trace;
        }

        private String pick() {
            return NODES.get(random.nextInt(NODES.size()));
        }

        private String pairKey(String x, String y) {
            return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
        }

        private void toggleCut() {
            String x = pick();
            String y = pick();
            if (x.equals(y)) {
                return;
            }
            String key = pairKey(x, y);
            if (!cut.remove(key)) {
                cut.add(key);
            }
            trace.add("cut/reconnect " + key);
        }

        private void meet() throws IOException {
            String x = pick();
            String y = pick();
            if (!x.equals(y) && !cut.contains(pairKey(x, y))) {
                meeting(x, y);
            }
        }

        private void advance(String node) throws IOException {
            checkout(heads.get(node));
            Files.writeString(repo.resolve(node + ".txt"), "change " + (++counter) + "\n");
            GitProcessRunner.run(repo, "add", node + ".txt");
            GitProcessRunner.run(repo, "commit", "-m", node + " advance " + counter);
            String commit = GitProcessRunner.run(repo, "rev-parse", "HEAD");
            ownAdvanceCommits.add(commit);
            advances.get(node).add(commit);
            known.get(node).put(node, commit);
            trace.add(node + " advance " + abbreviate(commit));
            move(node, commit);
        }

        /**
         * One meeting of a connected pair: they exchange what they know of every writer's last advance, then each runs
         * the fast-forward step and the re-merge step. Whether any copy or any knowledge changed.
         */
        private boolean meeting(String x, String y) throws IOException {
            boolean changed = exchange(x, y);
            changed |= fastForward(x);
            changed |= remergeStep(x);
            changed |= fastForward(y);
            changed |= remergeStep(y);
            return changed;
        }

        /** Each learns the later of the two views of every writer's last advance. */
        private boolean exchange(String x, String y) throws IOException {
            boolean changed = false;
            for (String writer : NODES) {
                String hx = known.get(x).get(writer);
                String hy = known.get(y).get(writer);
                String later = later(hx, hy);
                if (later != null && !later.equals(hx)) {
                    known.get(x).put(writer, later);
                    changed = true;
                }
                if (later != null && !later.equals(hy)) {
                    known.get(y).put(writer, later);
                    changed = true;
                }
            }
            return changed;
        }

        private String later(String p, String q) throws IOException {
            if (p == null || q == null) {
                return p == null ? q : p;
            }
            if (p.equals(q) || GitProcessRunner.isAncestor(repo, p, q)) {
                return q;
            }
            assertTrue(GitProcessRunner.isAncestor(repo, q, p), fail("a writer's advances do not form a chain: " + p + ", " + q));
            return p;
        }

        /** Step 1: fast-forward to every known head this copy is behind, in writer order. */
        private boolean fastForward(String node) throws IOException {
            boolean moved = false;
            for (Map.Entry<String, String> last : new TreeMap<>(known.get(node)).entrySet()) {
                if (MainMeeting.relate(repo, heads.get(node), last.getValue()) == Relation.BEHIND) {
                    trace.add(node + " ff to " + last.getKey() + "'s " + abbreviate(last.getValue()));
                    move(node, last.getValue());
                    moved = true;
                }
            }
            return moved;
        }

        /** Step 2: RemergeService.run. */
        private boolean remergeStep(String node) throws IOException {
            String own = heads.get(node);
            Map<String, String> diverged = new TreeMap<>();
            for (Map.Entry<String, String> last : new TreeMap<>(known.get(node)).entrySet()) {
                if (last.getKey().equals(node)) {
                    continue;
                }
                Relation relation = MainMeeting.relate(repo, own, last.getValue());
                if (relation == Relation.BEHIND) {
                    return false;
                }
                if (relation == Relation.DIVERGED) {
                    diverged.put(last.getKey(), last.getValue());
                }
            }
            Map<String, String> inTheWay = new TreeMap<>();
            for (Map.Entry<String, String> copy : diverged.entrySet()) {
                boolean held = false;
                for (String other : diverged.values()) {
                    held |= !other.equals(copy.getValue()) && GitProcessRunner.isAncestor(repo, copy.getValue(), other);
                }
                if (!held) {
                    inTheWay.put(copy.getKey(), copy.getValue());
                }
            }
            if (inTheWay.isEmpty() || inTheWay.keySet().stream().anyMatch(writer -> writer.compareTo(node) > 0)) {
                return false;
            }
            remerge(node, inTheWay.keySet().iterator().next(), inTheWay.values().iterator().next());
            return true;
        }

        /** The re-merge onto the first copy in the way; the copy moves to the result, which is its newest advance. */
        private void remerge(String node, String lowerWriter, String other) throws IOException {
            assertTrue(node.compareTo(lowerWriter) > 0, fail("the lower node " + node + " would re-merge onto " + lowerWriter));
            String own = heads.get(node);
            checkout(other);
            for (String advance : List.copyOf(advances.get(node))) {
                if (!GitProcessRunner.isAncestor(repo, advance, other) && !GitProcessRunner.isAncestor(repo, advance, "HEAD")) {
                    mergeInto(advance);
                }
            }
            if (!GitProcessRunner.isAncestor(repo, own, "HEAD")) {
                mergeInto(own);
            }
            String result = GitProcessRunner.run(repo, "rev-parse", "HEAD");
            advances.get(node).add(result);
            known.get(node).put(node, result);
            trace.add(node + " re-merges onto " + lowerWriter + "'s " + abbreviate(other) + " -> " + abbreviate(result));
            move(node, result);
        }

        private void mergeInto(String commit) throws IOException {
            GitProcessRunner.run(repo, "merge", "--no-ff", "-m", "re-merge " + abbreviate(commit), commit);
        }

        private void checkout(String commit) throws IOException {
            GitProcessRunner.run(repo, "checkout", "-q", "--detach", commit);
        }

        /** Records a head change; it must go to a descendant of the previous head. */
        private void move(String node, String to) throws IOException {
            String from = heads.get(node);
            assertTrue(!from.equals(to) && GitProcessRunner.isAncestor(repo, from, to),
                    fail(node + " moved from " + from + " to non-descendant " + to));
            heads.put(node, to);
        }

        private static String abbreviate(String commit) {
            return commit.substring(0, 8);
        }
    }
}
