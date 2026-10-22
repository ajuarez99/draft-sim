package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The flag filter that decides what reaches the log. The one that matters: a -D (which can
 * carry a datasource password) is never echoed.
 */
class JvmSettingsLoggerTest {

    @Test
    void keepsXFlagsAndDropsEverythingElse() {
        List<String> in = List.of(
                "-Xmx1024m",
                "-Dspring.datasource.password=x",
                "-XX:+UseG1GC",
                "-javaagent:/a.jar",
                "-XX:G1PeriodicGCInterval=60000",
                "-ea");
        assertEquals(List.of("-Xmx1024m", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=60000"),
                JvmSettingsLogger.jvmFlags(in));
    }

    @Test
    void dropsTheFlagsThatTakeAShellCommand() {
        List<String> in = List.of(
                "-XX:OnOutOfMemoryError=curl -H 'X-Token: s3cret' https://x",
                "-XX:OnError=echo s3cret",
                "-XX:+ExitOnOutOfMemoryError");
        assertEquals(List.of("-XX:+ExitOnOutOfMemoryError"), JvmSettingsLogger.jvmFlags(in));
    }

    @Test
    void noArgumentsGivesNoFlags() {
        assertEquals(List.of(), JvmSettingsLogger.jvmFlags(List.of()));
    }
}
