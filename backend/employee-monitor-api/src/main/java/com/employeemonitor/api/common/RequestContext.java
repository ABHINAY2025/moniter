package com.employeemonitor.api.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/** Access to the current HTTP request outside of controllers. */
public final class RequestContext {

    private RequestContext() {
    }

    public static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }

    /**
     * Client IP as seen by the servlet container. Proxy headers are honoured only when
     * server.forward-headers-strategy is enabled (i.e. when deployed behind a trusted reverse proxy).
     */
    public static String clientIp() {
        return currentRequest().map(HttpServletRequest::getRemoteAddr).orElse(null);
    }
}
