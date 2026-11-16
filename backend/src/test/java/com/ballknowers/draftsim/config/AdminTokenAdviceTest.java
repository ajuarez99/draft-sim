package com.ballknowers.draftsim.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lock fix pass, CR-03: one startup WARN for a short admin token, never the token itself. */
class AdminTokenAdviceTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(WebConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> warnFor(String token) {
        AdminAccess admin = new AdminAccess(new AdminProperties(token));
        LeagueLockProperties lock = new LeagueLockProperties(true, null, null);
        new WebConfig(null, null, admin, null, lock,
                new LeagueSession(new AdminProperties(token), lock, Clock.systemUTC())).warnIfAdminTokenIsShort();
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void aShortTokenWarnsOnceWithoutRevealingIt() {
        List<ILoggingEvent> warns = warnFor("hunter2-but-longer");
        assertEquals(1, warns.size());
        String text = warns.get(0).getFormattedMessage();
        assertTrue(text.contains("shorter than 32 characters"), text);
        assertTrue(text.contains("openssl rand -base64 32"), text);
        assertFalse(text.contains("hunter2"), "the token must not be logged");
        assertFalse(text.contains("18"), "nor its length");
    }

    @Test
    void aLongEnoughTokenAndABlankOneDoNotWarn() {
        assertTrue(warnFor("x".repeat(32)).isEmpty(), "exactly 32 is long enough");
        assertTrue(warnFor("x".repeat(31)).size() == 1, "31 is not");
        appender.list.clear();
        assertTrue(warnFor(null).isEmpty());
        assertTrue(warnFor("   ").isEmpty());
    }
}
