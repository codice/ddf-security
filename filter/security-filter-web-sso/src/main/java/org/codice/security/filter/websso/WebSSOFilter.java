/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package org.codice.security.filter.websso;

import org.codice.security.filter.api.AuthenticationHandler;
import org.codice.security.filter.api.FilterResult;
import org.codice.security.filter.api.FilterResult.FilterStatus;
import org.codice.security.filter.saml.SAMLAssertionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebSSOFilter implements Filter {
    private static final String DDF_SECURITY_TOKEN = "ddf.security.securityToken";

    private static final String DDF_AUTHENTICATION_TOKEN = "ddf.security.token";

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSSOFilter.class);

    ArrayList<AuthenticationHandler> authenticationHandlers = new ArrayList<AuthenticationHandler>();

    List<AuthenticationHandler> handlerList;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // hard coded for now - this will be read from the config and dynamically assigned in the future
//        authenticationHandlers.add(new SAMLAssertionHandler());
//        authenticationHandlers.addAll(handlerList);
//        authenticationHandlers.add(new AnonymousHandler());
    }

    @Override
    public synchronized void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        authenticationHandlers = new ArrayList<AuthenticationHandler>();
        authenticationHandlers.add(new SAMLAssertionHandler());
        authenticationHandlers.addAll(handlerList);

        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        String path = httpRequest.getServletPath();
        LOGGER.debug("Handling request for path {}", path);

        // read configuration for this path - get the authentication type and the roles required
        // for now...
        Map<String, Cookie> cookies = FilterUtils.getCookieMap(httpRequest);

        // First pass, see if anyone can come up with proper security token from the git-go
        FilterResult result = null;
        for (AuthenticationHandler auth : authenticationHandlers) {
            result = auth.getNormalizedToken(servletRequest, servletResponse, filterChain, false);
            if (result.getStatus() != FilterStatus.NO_ACTION) {
                break;
            }
        }

        // If we haven't received usable credentials yet, go get some
        if (result == null || result.getStatus() == FilterStatus.NO_ACTION) {
            LOGGER.debug("First pass with no tokens found - requesting tokens");
            // This pass, tell each handler to do whatever it takes to get a SecurityToken
            for (AuthenticationHandler auth : authenticationHandlers) {
                result = auth.getNormalizedToken(servletRequest, servletResponse, filterChain, true);
                if (result.getStatus() != FilterStatus.NO_ACTION) {
                    break;
                }
            }
        }

        switch (result.getStatus()) {
            case REDIRECTED:
                LOGGER.debug("Stopping filter chain - handled by plugins");
                // return without invoking the remaining chain
                return;
            case NO_ACTION: // should never occur
            case COMPLETED:
                // set the appropriate request attribute
                if (result.hasSecurityToken()) {
                    LOGGER.debug("Attaching SecurityToken to http request");
                    httpRequest.setAttribute(DDF_SECURITY_TOKEN, result.getCredentials());
                } else {
                    LOGGER.debug("Attaching AuthenticationToken to http request");
                    httpRequest.setAttribute(DDF_AUTHENTICATION_TOKEN, result);
                }
                break;
        }

        // If we got here, we've received our tokens to continue
        LOGGER.debug("Invoking the rest of the filter chain");
        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {

    }

    public List<AuthenticationHandler> getHandlerList() {
        return handlerList;
    }

    public void setHandlerList(List<AuthenticationHandler> handlerList) {
        this.handlerList = handlerList;
    }

    /**
     * Returns a mapping of cookies from the incoming request. Key is the cookie name, while the
     * value is the Cookie object itself.
     *
     * @param req Servlet request for this call
     * @return map of Cookie objects present in the current request - always returns a map
     */
    public static Map<String, Cookie> getCookieMap(HttpServletRequest req) {
        HashMap<String, Cookie> map = new HashMap<String, Cookie>();

        Cookie[] cookies = req.getCookies();
        for (Cookie cookie : cookies) {
            map.put(cookie.getName(), cookie);
        }

        return map;
    }
}
