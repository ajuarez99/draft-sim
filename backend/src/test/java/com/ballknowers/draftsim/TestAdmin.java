package com.ballknowers.draftsim;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Lets an integration test call a controller bean directly (no MockMvc, no
 * servlet container) as either an operator or an ordinary caller.
 *
 * <p>{@code AdminAccess.isAdmin()} reads {@code X-Admin-Token} off the current
 * request via {@code RequestContextHolder}, so a direct call needs a request
 * bound to the thread first. {@link #TOKEN} is the value {@code build.gradle.kts}
 * sets as {@code draftsim.admin.token} for every test JVM; a test that wants the
 * admin route DISABLED overrides that property with a blank instead.
 *
 * <p>Always close the returned handle (try-with-resources): a request left bound
 * to a pooled test thread would make the next test "admin" for free.
 */
public final class TestAdmin {

    public static final String TOKEN = "it-admin-token";

    private TestAdmin() {}

    /** Binds a request carrying the given {@code X-Admin-Token} (null for none). */
    public static Scope withToken(String presented) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (presented != null) request.addHeader("X-Admin-Token", presented);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return RequestContextHolder::resetRequestAttributes;
    }

    /** A request carrying the correct admin token. */
    public static Scope asAdmin() {
        return withToken(TOKEN);
    }

    /** Unbinds on close. Not {@code AutoCloseable}, so no checked exception to declare. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
