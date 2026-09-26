package com.iridium.flowerdisease;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

// A safety net for a game that seems frozen - "Saving world" that never ends when leaving a world, say. If one server tick, the
// stopping of the world, or the server thread's last moments after that take longer than `stallReportSeconds`, a report is written to
// the logs folder: what every thread is doing
// (which shows where the server thread is stuck and whether any thread is deadlocked), how much memory is used and what the garbage
// collector has been up to, and the state of the chunk system of each dimension (vanilla's own level debug report, whose chunk list
// says which chunk is not ready to be saved). It is meant for the one thing a bug report cannot say: where a game that will not go on
// is waiting. Nothing but a sleeping thread runs while the game is fine, and the report is at most three times per stall.
//
// The watching is done by a daemon thread that only reads what the server thread says about itself (when the tick in progress began,
// when the world began to stop), so it works whatever the server thread is stuck in - including a tick that never ends, which no
// code running on the server thread could report.
final class StallWatchdog {
    private static final long POLL_MILLIS = 2_000L;
    private static final int MAX_REPORTS_PER_STALL = 3;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    // System.nanoTime() when the tick in progress began, when the world began to stop and when it finished stopping (the server thread
    // is not done until it has closed everything, and a client leaving the world waits for it); 0 when none of those is going on. (A
    // paused integrated server runs no ticks at all, which is why a tick is measured from its start instead of from the last one.)
    private static volatile long tickStartedAt;
    private static volatile long stopStartedAt;
    private static volatile long stoppedAt;
    @Nullable
    private static volatile Thread watcher;

    private StallWatchdog() {
    }

    static void start(MinecraftServer server) {
        start(server, Config.STALL_REPORT_SECONDS.getAsInt());
    }

    // `seconds` is how long a stall has to last before it is reported (0 = never); the game tests use a short one.
    static void start(MinecraftServer server, int seconds) {
        finish();
        if (seconds <= 0) {
            return;
        }

        tickStartedAt = 0L;
        stopStartedAt = 0L;
        stoppedAt = 0L;
        Thread thread = new Thread(() -> watch(server, seconds * 1_000_000_000L), "flowerdisease-stall-watchdog");
        thread.setDaemon(true);
        watcher = thread;
        thread.start();
    }

    static void tickStarted() {
        tickStartedAt = System.nanoTime();
    }

    static void tickFinished() {
        tickStartedAt = 0L;
    }

    static void stopping() {
        stopStartedAt = System.nanoTime();
    }

    // The world has stopped: the watcher stays until the server thread has ended too, in case that is what never happens.
    static void stopped() {
        stoppedAt = System.nanoTime();
    }

    // Ends the watching (a new server, or nothing left to watch).
    static void finish() {
        Thread thread = watcher;
        watcher = null;
        tickStartedAt = 0L;
        stopStartedAt = 0L;
        stoppedAt = 0L;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void watch(MinecraftServer server, long limitNanos) {
        int reports = 0;
        long lastReportAt = 0L;
        while (watcher == Thread.currentThread()) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }

            if (stoppedAt != 0L && server.isShutdown()) {
                // Everything is over, the server thread included.
                finish();
                return;
            }

            long now = System.nanoTime();
            long stopped = stoppedAt;
            long stopping = stopStartedAt;
            long ticking = tickStartedAt;
            String what = null;
            if (stopped != 0L && now - stopped > limitNanos) {
                what = "the server thread is still running " + (now - stopped) / 1_000_000_000L + " s after the world stopped";
            } else if (stopping != 0L && now - stopping > limitNanos) {
                what = "the world has been stopping for " + (now - stopping) / 1_000_000_000L + " s";
            } else if (ticking != 0L && now - ticking > limitNanos) {
                what = "one server tick has been running for " + (now - ticking) / 1_000_000_000L + " s";
            }

            if (what == null) {
                reports = 0;
                continue;
            }
            if (reports < MAX_REPORTS_PER_STALL && now - lastReportAt >= limitNanos) {
                reports++;
                lastReportAt = now;
                report(server, what);
            }
        }
    }

    // Writes the report and returns the folder it is in (null if it could not be written).
    @Nullable
    static Path report(MinecraftServer server, String what) {
        Path folder = FMLPaths.GAMEDIR.get().resolve("logs").resolve("flowerdisease-stall-" + LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(folder);
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(folder.resolve("threads.txt")))) {
                writeSummary(out, server, what);
            }
        } catch (IOException | RuntimeException e) {
            FlowerDisease.LOGGER.error("Could not write the stall report to {}", folder, e);
            return null;
        }

        // Vanilla's level dump, one folder per dimension: the chunk system's state, the tickets, the entities. Read from this thread
        // while the server thread is stuck, so it may trip over something the server thread is changing - never the game's problem.
        for (ServerLevel level : server.getAllLevels()) {
            Path levelFolder = folder.resolve(level.dimension().location().getPath());
            try {
                Files.createDirectories(levelFolder);
                level.saveDebugReport(levelFolder);
            } catch (IOException | RuntimeException e) {
                FlowerDisease.LOGGER.warn("The level dump of {} could not be completed: {}", level.dimension().location(), e.toString());
            }
        }
        FlowerDisease.LOGGER.warn("Flower Disease: {} - what the game is waiting for was written to {}", what, folder.toAbsolutePath());
        return folder;
    }

    private static void writeSummary(PrintWriter out, MinecraftServer server, String what) {
        Runtime runtime = Runtime.getRuntime();
        out.println("Flower Disease stall report: " + what);
        out.println("server tick count " + server.getTickCount() + ", heap " + (runtime.totalMemory() - runtime.freeMemory() >> 20) + " MB used of "
                + (runtime.maxMemory() >> 20) + " MB, " + Thread.getAllStackTraces().size() + " threads");
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.println("garbage collector " + collector.getName() + ": " + collector.getCollectionCount() + " collections, " + collector.getCollectionTime() + " ms in all");
        }

        for (ServerLevel level : server.getAllLevels()) {
            try {
                out.println(level.dimension().location() + ": " + level.getChunkSource().getLoadedChunksCount() + " chunks loaded, chunk system has work: "
                        + level.getChunkSource().chunkMap.hasWork() + ", pending chunk tasks: " + level.getChunkSource().getPendingTasksCount());
            } catch (RuntimeException e) {
                out.println(level.dimension().location() + ": could not be read (" + e + ")");
            }
        }

        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        out.println(deadlocked == null ? "no deadlocked threads" : "DEADLOCKED THREADS: " + deadlocked.length);
        out.println();

        // The server thread first, then the rest by name.
        List<Map.Entry<Thread, StackTraceElement[]>> threads = new ArrayList<>(Thread.getAllStackTraces().entrySet());
        threads.sort(Comparator.comparing((Map.Entry<Thread, StackTraceElement[]> entry) -> !entry.getKey().getName().equals("Server thread")).thenComparing(entry -> entry.getKey().getName()));
        for (Map.Entry<Thread, StackTraceElement[]> entry : threads) {
            out.println("=== " + entry.getKey().getName() + " (" + entry.getKey().getState() + ")");
            for (StackTraceElement frame : entry.getValue()) {
                out.println("    at " + frame);
            }
        }
    }
}
