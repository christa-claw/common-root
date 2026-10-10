// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs {@code scripts/print/build_package.py} for the order page's "Create print package" button.
 *
 * <p>The builder is a typesetting program: Python, ReportLab, the print fonts, the corpus in BaseX
 * and, for a thousand-page book, about ten seconds of CPU and 650 MB of memory. The production image carries none of that (it
 * is a JRE and the jar), so this service only works where the repository is: a developer's machine
 * running the app. {@link #enabled()} says whether it can, and it is off on a production build even if
 * the scripts happen to be there, because a public web request that costs a minute of a small server
 * is not something to expose. The controller lets only an administrator start a build.
 *
 * <p>One build at a time: a second request is refused while one runs. Each build writes to its own
 * directory under {@code out/print/orders/} (gitignored), only the last few are kept, and a build that
 * runs past the timeout is killed.
 */
@Service
public class PrintBuilderService {

    private static final Logger log = LoggerFactory.getLogger(PrintBuilderService.class);
    private static final String SCRIPT = "scripts/print/build_package.py";
    private static final int LOG_LINES = 60;
    private static final int KEEP = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Status { RUNNING, DONE, FAILED }

    /** One build: its progress while it runs, and what it made when it is done. */
    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final Instant started = Instant.now();
        private final Deque<String> lines = new ArrayDeque<>();
        private volatile Status status = Status.RUNNING;
        private volatile String error;
        private volatile Instant finished;
        private volatile JsonNode result;
        private volatile Path zip;
        private final String filename;

        Job(final String aFilename) { this.filename = aFilename; }

        public String id() { return id; }
        public Status status() { return status; }
        public String error() { return error; }
        public JsonNode result() { return result; }
        public Path zip() { return zip; }
        public String filename() { return filename; }

        public long elapsedSeconds() {
            return Duration.between(started, finished != null ? finished : Instant.now()).toSeconds();
        }

        public synchronized List<String> tail(final int aCount) {
            final List<String> all = List.copyOf(lines);
            return all.subList(Math.max(0, all.size() - aCount), all.size());
        }

        synchronized void add(final String aLine) {
            lines.addLast(aLine);
            while (lines.size() > LOG_LINES) lines.removeFirst();
        }
    }

    /** Thrown when a build is already running. */
    public static final class BusyException extends RuntimeException {
        private final String runningId;
        BusyException(final String aRunningId) {
            super("A package is already being built.");
            this.runningId = aRunningId;
        }
        public String runningId() { return runningId; }
    }

    private final BaseXProperties basex;
    private final String enabledSetting;
    private final String env;
    private final String python;
    private final Path root;
    private final int timeoutMinutes;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "print-builder");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "print-builder-watchdog");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public PrintBuilderService(final BaseXProperties aBaseX,
                               @Value("${religioustext.print-builder.enabled:auto}") final String anEnabled,
                               @Value("${religioustext.env:dev}") final String anEnv,
                               @Value("${religioustext.print-builder.python:}") final String aPython,
                               @Value("${religioustext.print-builder.repo-root:}") final String aRepoRoot,
                               @Value("${religioustext.print-builder.timeout-minutes:20}") final int aTimeout) {
        this.basex = aBaseX;
        this.enabledSetting = anEnabled;
        this.env = anEnv;
        this.python = !aPython.isBlank() ? aPython
            : Files.isExecutable(Path.of("/usr/bin/python3")) ? "/usr/bin/python3" : "python3";
        this.root = findRoot(aRepoRoot);
        this.timeoutMinutes = aTimeout;
    }

    /** The repository root: the configured one, else this directory or its parent (the app runs from app/). */
    private static Path findRoot(final String aConfigured) {
        if (!aConfigured.isBlank()) return Path.of(aConfigured).toAbsolutePath().normalize();
        final Path here = Path.of("").toAbsolutePath().normalize();
        for (final Path p : new Path[] {here, here.getParent()}) {
            if (p != null && Files.isRegularFile(p.resolve(SCRIPT))) return p;
        }
        return here;
    }

    /**
     * Whether this server can build a package: the script is there and the setting allows it.
     * {@code auto}, the default, means "yes unless this is a production build".
     */
    public boolean enabled() {
        if ("false".equalsIgnoreCase(enabledSetting)) return false;
        if (!Files.isRegularFile(root.resolve(SCRIPT))) return false;
        return "true".equalsIgnoreCase(enabledSetting) || !"prod".equalsIgnoreCase(env);
    }

    /** Start a build of an already validated request. */
    public synchronized Job submit(final PrintPackageRequest.Valid aRequest) {
        for (final Job j : jobs.values()) {
            if (j.status == Status.RUNNING) throw new BusyException(j.id);
        }
        final Job job = new Job(aRequest.filename());
        jobs.put(job.id, job);
        prune();
        worker.submit(() -> run(job, aRequest));
        return job;
    }

    public Job job(final String anId) { return anId == null ? null : jobs.get(anId); }

    /** How long a build may run before it is killed. Overridden in tests. */
    protected long timeoutMillis() { return timeoutMinutes * 60_000L; }

    /** The command: the interpreter, then the builder and its options. Overridden in tests. */
    protected List<String> buildCommand(final PrintPackageRequest.Valid aRequest, final Path anOut) {
        final List<String> cmd = new java.util.ArrayList<>();
        cmd.add(python);
        cmd.addAll(aRequest.arguments(SCRIPT, anOut));
        return cmd;
    }

    private void run(final Job aJob, final PrintPackageRequest.Valid aRequest) {
        Process process = null;
        ScheduledFuture<?> kill = null;
        try {
            final Path dir = root.resolve("out/print/orders").resolve(aJob.id);
            Files.createDirectories(dir);
            final Path out = dir.resolve("package.zip");
            final List<String> cmd = buildCommand(aRequest, out);
            final ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
            // The builder reads the corpus from the same BaseX this app does.
            pb.environment().put("BASEX_URL", basex.uri() + "/" + basex.database());
            pb.environment().put("BASEX_AUTH", basex.username() + ":" + basex.password());
            log.info("Print builder: {}", String.join(" ", cmd));
            process = pb.start();
            final Process p = process;
            kill = watchdog.schedule(() -> {
                aJob.add("Stopped: the build ran past its time limit (" + timeoutMinutes + " minutes).");
                p.destroyForcibly();
            }, timeoutMillis(), TimeUnit.MILLISECONDS);

            String resultLine = null;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("RESULT ")) resultLine = line.substring(7);
                    else if (!line.isBlank()) aJob.add(line);
                }
            }
            final int code = process.waitFor();
            if (code == 0 && resultLine != null && Files.isRegularFile(out)) {
                aJob.result = JSON.readTree(resultLine);
                aJob.zip = out;
                aJob.status = Status.DONE;
            } else {
                aJob.error = aJob.tail(1).isEmpty() ? "The build produced nothing."
                    : "The build failed (exit " + code + "): " + aJob.tail(1).get(0);
                aJob.status = Status.FAILED;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            aJob.error = "The build was interrupted.";
            aJob.status = Status.FAILED;
        } catch (final IOException | RuntimeException e) {
            log.warn("Print builder failed", e);
            aJob.error = "The build could not run: " + e.getMessage();
            aJob.status = Status.FAILED;
        } finally {
            if (kill != null) kill.cancel(false);
            if (process != null && process.isAlive()) process.destroyForcibly();
            aJob.finished = Instant.now();
        }
    }

    /** Keep the last few builds on disk; the rest are removed. */
    private void prune() {
        final Path orders = root.resolve("out/print/orders");
        if (!Files.isDirectory(orders)) return;
        try (Stream<Path> dirs = Files.list(orders)) {
            final List<Path> old = dirs.filter(Files::isDirectory)
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                .skip(KEEP).toList();
            for (final Path d : old) {
                try (Stream<Path> files = Files.walk(d)) {
                    files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                }
                jobs.remove(d.getFileName().toString());
            }
        } catch (final IOException e) {
            log.debug("Could not prune old print builds", e);
        }
    }
}
