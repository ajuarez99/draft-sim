package com.ballknowers.draftsim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Logs the JVM settings the process actually started with, once, so production's effective
 * flags can be read from the deploy log rather than inferred (specs/041-memory-footprint,
 * FR-012). Only -X flags are echoed: a -D can carry a secret and must never reach the log.
 */
@Component
public class JvmSettingsLogger {

    private static final Logger log = LoggerFactory.getLogger(JvmSettingsLogger.class);

    @EventListener(ApplicationReadyEvent.class)
    public void logJvmSettings() {
        List<String> gcs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList();
        log.info("JVM settings: maxHeapMb={} gc={} availableProcessors={} flags={}",
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                gcs,
                Runtime.getRuntime().availableProcessors(),
                jvmFlags(ManagementFactory.getRuntimeMXBean().getInputArguments()));
    }

    /**
     * Keeps only -X / -XX: flags; everything else (-D, -javaagent, -ea ...) is dropped. The two -XX flags
     * that take a shell command ({@code OnError}, {@code OnOutOfMemoryError}) are dropped too, since a
     * command line can carry a token (spec 041 code review #6).
     */
    static List<String> jvmFlags(List<String> args) {
        return args.stream()
                .filter(a -> a.startsWith("-X"))
                .filter(a -> !a.startsWith("-XX:OnError") && !a.startsWith("-XX:OnOutOfMemoryError"))
                .toList();
    }
}
