package com.ballknowers.draftsim.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * "Did this request present a valid admin token?" -- the one place that
 * question is answered.
 *
 * <p>{@link #isAdmin()} reads the current request's {@code X-Admin-Token}
 * through {@link RequestContextHolder}, so the membership checks
 * ({@code LeagueMembership}, {@code MockDraftService}) can honour the override
 * without every controller threading a flag through. It is false on any thread
 * with no request (the live poller, a scheduled job, a unit test that never set
 * one), which is the safe direction.
 *
 * <p>Controllers that gate a whole route on admin call {@link #matches} with the
 * header they already bound.
 */
@Component
public class AdminAccess {

    public static final String HEADER = "X-Admin-Token";

    private final AdminProperties props;

    public AdminAccess(AdminProperties props) {
        this.props = props;
    }

    /** Whether an admin token is configured at all. Blank means every admin-only route refuses. */
    public boolean enabled() {
        return props.enabled();
    }

    public boolean matches(String presentedToken) {
        return props.matches(presentedToken);
    }

    public boolean isAdmin() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) return false;
        HttpServletRequest request = servlet.getRequest();
        return props.matches(request.getHeader(HEADER));
    }
}
