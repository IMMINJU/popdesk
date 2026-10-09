package io.popdesk;

import io.popdesk.PublicAddress.App;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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
 * <p>Spring Boot's own static mapping is off ({@code spring.web.resources.add-mappings=false}), so
 * one app's files are never served on the other app's host. Without a build in the classpath (tests,
 * {@code spring-boot:run}) every screen path is a 404.
 */
@Configuration(proxyBeanMethods = false)
class AppScreens implements WebMvcConfigurer {

    /** The resource handler takes an empty path for a missing file, so {@code /} goes to the index first. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(new ClassPathResource("static/"))
                .resourceChain(false)
                .addResolver(new HostResolver());
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
            Resource folder = new ClassPathResource("static/" + app.name().toLowerCase(Locale.ROOT) + "/");
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
