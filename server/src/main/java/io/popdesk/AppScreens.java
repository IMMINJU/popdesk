package io.popdesk;

import io.popdesk.PublicAddress.App;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

/**
 * The screens of the two apps (architecture.md section 2): the image puts the portal build in
 * {@code classpath:static/portal/} and the console build in {@code classpath:static/console/}, and
 * each host gets its own app's files. A path with no file and no extension is a screen of the app's
 * router, so it gets {@code index.html}. Paths under {@code /api/} never do: an unknown API path stays
 * a JSON 404. Only GET and HEAD are served, after host routing and with the security headers, like
 * every other response.
 *
 * <p>Files under {@code assets/} carry a hash of their contents in their names, so a browser or proxy
 * may keep them for a year ({@code public, max-age=31536000, immutable}). Everything else, first of
 * all {@code index.html}, which names those files, is {@code no-cache}: kept, but asked for again
 * every time, so a new build is seen at once (S1A-AC-61). The header is set here; Spring Security's
 * {@code no-store} is only written to a response that has no {@code Cache-Control} yet.
 *
 * <p>Spring Boot's own static mapping is off ({@code spring.web.resources.add-mappings=false}), so
 * one app's files are never served on the other app's host. Without a build in the classpath (tests,
 * {@code spring-boot:run}) every screen path is a 404.
 */
@Configuration(proxyBeanMethods = false)
class AppScreens implements WebMvcConfigurer {

    /** The folder of a build's hashed files, as a path under the app's folder. */
    static final String ASSETS = "assets/";

    /** The resource handler takes an empty path for a missing file, so {@code /} goes to the index first. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/" + ASSETS + "**")
                .addResourceLocations(new ClassPathResource("static/"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(false)
                .addResolver(new AssetResolver());
        registry.addResourceHandler("/**")
                .addResourceLocations(new ClassPathResource("static/"))
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new HostResolver());
    }

    private static Resource folder(App app) {
        return new ClassPathResource("static/" + app.name().toLowerCase(Locale.ROOT) + "/");
    }

    /**
     * Finds a hashed file in {@code assets/} of the request host's app. A name with no file is a 404,
     * never {@code index.html}: a page kept under a file's name for a year would not go away.
     */
    static class AssetResolver extends PathResourceResolver {

        @Override
        protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                List<? extends Resource> locations, ResourceResolverChain chain) {
            App app = request == null ? null : HostRoutingFilter.app(request);
            if (app == null || requestPath.isEmpty()) {
                return null;
            }
            try {
                return getResource(ASSETS + requestPath, folder(app));
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Finds a file in the folder of the request host's app, else that app's {@code index.html}. */
    static class HostResolver extends PathResourceResolver {

        private static final String INDEX = "index.html";

        @Override
        protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                List<? extends Resource> locations, ResourceResolverChain chain) {
            App app = request == null ? null : HostRoutingFilter.app(request);
            if (app == null || requestPath.equals("api") || requestPath.startsWith("api/")) {
                return null;
            }
            Resource folder = folder(app);
            try {
                if (!requestPath.isEmpty()) {
                    Resource file = getResource(requestPath, folder);
                    if (file != null) {
                        return file;
                    }
                    String last = requestPath.substring(requestPath.lastIndexOf('/') + 1);
                    if (last.contains(".")) {
                        return null;
                    }
                }
                return getResource(INDEX, folder);
            } catch (IOException e) {
                return null;
            }
        }
    }
}
