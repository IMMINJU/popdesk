package io.popdesk.helpdesk.account;

import io.popdesk.ApiError;
import io.popdesk.ApiErrors;
import io.popdesk.ForwardedHeadersFilter;
import io.popdesk.HostRoutingFilter;
import io.popdesk.PublicAddress;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The security filter chain (architecture.md section 5, D-40, D-41). In order:
 *
 * <ol>
 *   <li>Security headers on every response, errors included (S0-AC-16): CSP, nosniff, no referrer,
 *       and Spring Security's {@code Cache-Control: no-store} (S0-AC-101).
 *   <li>Forwarded headers from trusted proxies (S0-AC-18, S0-AC-19), then host routing (S0-AC-13,
 *       S0-AC-14), then 503 while there is no first admin (S0-AC-02). All three run after the header
 *       writer, so their responses carry the headers too.
 *   <li>CSRF: a readable {@code XSRF-TOKEN} cookie, returned as is in {@code X-XSRF-TOKEN} (S0-AC-17).
 *   <li>The session's account, read from the database on every request (S0-AC-26).
 *   <li>The role check, before the target is looked up (COM-2): 401 without a session, 403 for an
 *       endpoint of another role. It applies to the endpoints the app maps; any other path goes on to
 *       a 404.
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String CSP = "default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    @Bean
    CsrfTokenRepository csrfTokenRepository(PublicAddress address) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(address.secure()));
        return repository;
    }

    /** The session cookie: {@code HttpOnly}, {@code SameSite=Lax}, host-only, {@code Secure} on https. */
    @Bean
    CookieSerializer cookieSerializer(PublicAddress address) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setCookiePath("/");
        serializer.setUseSecureCookie(address.secure());
        return serializer;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PublicAddress address, Environment environment,
            CsrfTokenRepository csrfTokens, Sessions sessions, AccountRepository accounts,
            ObjectProvider<FirstAdminSetup> setup,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) throws Exception {
        RequestMatcher mapped = new MappedEndpoint(mappings);
        http
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .sessionManagement(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated())
                        .accessDeniedHandler(denied()))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(PATHS.matcher("/api/public/**")).permitAll()
                        .requestMatchers(new AndRequestMatcher(mapped, adminOnly())).hasRole("ADMIN")
                        .requestMatchers(new AndRequestMatcher(mapped, PATHS.matcher("/api/console/**")))
                                .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(new AndRequestMatcher(mapped, PATHS.matcher("/api/portal/**")))
                                .hasRole("CUSTOMER")
                        .anyRequest().permitAll())
                .addFilterAfter(new ForwardedHeadersFilter(environment.getProperty("POPDESK_TRUSTED_PROXIES"),
                        address), HeaderWriterFilter.class)
                .addFilterAfter(new HostRoutingFilter(address, environment), ForwardedHeadersFilter.class)
                .addFilterAfter(new SetupRequiredFilter(setup), HostRoutingFilter.class)
                .addFilterBefore(new SessionFilter(sessions, accounts), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** Endpoints for admins only (docs/use/permissions.md, S0-AC-30, S0-AC-33, S0-AC-40). */
    private static RequestMatcher adminOnly() {
        return new OrRequestMatcher(
                PATHS.matcher("/api/console/accounts/**"),
                PATHS.matcher(HttpMethod.POST, "/api/console/companies"),
                PATHS.matcher("/api/console/companies/*/agents/**"),
                PATHS.matcher("/api/console/companies/*/customers/**"),
                PATHS.matcher("/api/console/invitations/**"),
                PATHS.matcher("/api/console/system/**"));
    }

    private static AuthenticationEntryPoint unauthenticated() {
        return (request, response, e) -> ApiErrors.write(response, 401,
                new ApiError(ApiErrors.UNAUTHENTICATED, ApiErrors.UNAUTHENTICATED_MESSAGE, null));
    }

    private static AccessDeniedHandler denied() {
        return (request, response, e) -> ApiErrors.write(response, 403, e instanceof CsrfException
                ? new ApiError(ApiErrors.CSRF_TOKEN_INVALID, ApiErrors.CSRF_TOKEN_INVALID_MESSAGE, null)
                : new ApiError(ApiErrors.FORBIDDEN, ApiErrors.FORBIDDEN_MESSAGE, null));
    }

    /** Whether a controller of the app maps the request's path (with any method). */
    private record MappedEndpoint(RequestMappingHandlerMapping mappings) implements RequestMatcher {

        @Override
        public boolean matches(HttpServletRequest request) {
            Object parsed = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
            if (parsed == null) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            try {
                HandlerExecutionChain handler = mappings.getHandler(request);
                return handler != null;
            } catch (ServletException e) {
                // A mapping matched the path but not the method or media type: the endpoint exists.
                return true;
            } catch (Exception e) {
                throw new IllegalStateException("Could not match the request to an endpoint", e);
            } finally {
                if (parsed == null) {
                    ServletRequestPathUtils.clearParsedRequestPath(request);
                }
            }
        }
    }
}
