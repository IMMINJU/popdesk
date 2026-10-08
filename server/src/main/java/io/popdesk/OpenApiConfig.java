package io.popdesk;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two contract groups (ADR D-43) and the rules that shape the generated contract files.
 *
 * <ul>
 *   <li>Every error response (4xx, 5xx) has the {@link ApiError} body (COM-3).
 *   <li>Response types list every field as required, and a field marked {@code @Schema(nullable =
 *       true)} may be {@code null} (ADR D-44: fields are never left out). Request types, whose names end in
 *       {@code Request}, keep optional fields optional. {@code ApiError.fields} is the one response
 *       field that may be left out.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    /** The release this contract ships in. Change it with the release, not with the build. */
    static final String CONTRACT_VERSION = "0.1.0";

    @Bean
    GroupedOpenApi portalApi() {
        return group("portal", "popdesk portal API",
                "The customer portal: `/api/portal/**` on the portal host, and `/api/public/**`.",
                "/api/portal/**");
    }

    @Bean
    GroupedOpenApi consoleApi() {
        return group("console", "popdesk console API",
                "The agent console: `/api/console/**` on the console host, and `/api/public/**`.",
                "/api/console/**");
    }

    /**
     * Writes {@code @Schema(nullable = true)} the OpenAPI 3.1 way: {@code "null"} among the types. A
     * reference is not resolved yet here; {@link #requiredResponseFields()} turns it into {@code oneOf}.
     */
    @Bean
    PropertyCustomizer nullableProperties() {
        return (property, type) -> {
            boolean nullable = Boolean.TRUE.equals(property.getNullable());
            Annotation[] annotations = type.getCtxAnnotations();
            if (annotations != null) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof io.swagger.v3.oas.annotations.media.Schema schema && schema.nullable()) {
                        nullable = true;
                    }
                }
            }
            return nullable ? nullable(property) : property;
        };
    }

    private static GroupedOpenApi group(String name, String title, String description, String appPaths) {
        return GroupedOpenApi.builder()
                .group(name)
                .pathsToMatch(appPaths, "/api/public/**")
                .addOpenApiCustomizer(info(title, description))
                .addOpenApiCustomizer(errorBodies())
                .addOpenApiCustomizer(requiredResponseFields())
                .build();
    }

    private static OpenApiCustomizer info(String title, String description) {
        return openApi -> openApi.info(new Info()
                .title(title)
                .version(CONTRACT_VERSION)
                .description(description + " Conventions: docs/integrate/conventions.md.")
                .license(new License().name("Apache-2.0").identifier("Apache-2.0")));
    }

    private static OpenApiCustomizer errorBodies() {
        return openApi -> {
            Map<String, Schema> resolved = ModelConverters.getInstance(true)
                    .readAll(new AnnotatedType(ApiError.class));
            resolved.forEach(openApi.getComponents()::addSchemas);
            Content errorContent = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
                    char first = entry.getKey().charAt(0);
                    if (first == '4' || first == '5') {
                        entry.getValue().setContent(errorContent);
                    }
                }
            }));
        };
    }

    private static OpenApiCustomizer requiredResponseFields() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            if (schemas == null) {
                return;
            }
            schemas.forEach((name, schema) -> {
                if (schema.getProperties() == null) {
                    return;
                }
                // A nullable reference comes out of the PropertyCustomizer as {$ref, type: null}.
                schema.getProperties().replaceAll((property, value) -> nullableRef((Schema) value));
                if (name.endsWith("Request")) {
                    return;
                }
                Set<String> required = new LinkedHashSet<>(schema.getProperties().keySet());
                if (name.equals("ApiError")) {
                    required.remove("fields");
                }
                schema.setRequired(new ArrayList<>(required));
            });
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullableRef(Schema property) {
        if (property.get$ref() == null || property.getTypes() == null || !property.getTypes().contains("null")) {
            return property;
        }
        Schema wrapper = new Schema<>();
        wrapper.setOneOf(List.of(new Schema<>().$ref(property.get$ref()), new Schema<>().types(Set.of("null"))));
        wrapper.setDescription(property.getDescription());
        return wrapper;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        property.setNullable(null);
        Set<String> types = property.getTypes() != null ? new LinkedHashSet<>(property.getTypes()) : new LinkedHashSet<>();
        if (property.getType() != null) {
            types.add(property.getType());
        }
        types.add("null");
        property.setTypes(types);
        return property;
    }
}
