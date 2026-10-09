package io.popdesk.deploy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Compose file read as data, either as written (YAML) or as {@code docker compose config} resolved
 * it (JSON). Ports and mounts are read in both the short ({@code "8080:8080"}) and the long form.
 */
final class ComposeModel {

    /** A published port. {@code hostIp} is null when the port is on all interfaces. */
    record Port(String hostIp, String published, int target) {}

    /** A volume or bind mount of a service. */
    record Mount(String type, String source, String target) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String origin;
    private final Map<String, Object> root;

    private ComposeModel(String origin, Map<String, Object> root) {
        this.origin = origin;
        this.root = root;
    }

    /** The file as written. */
    static ComposeModel read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new AssertionError(file + " is not a YAML mapping");
            }
            return new ComposeModel(file.toString(), cast(map));
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file, e);
        }
    }

    /** What {@code docker compose config --format json} gave. */
    static ComposeModel resolved(JsonNode config) {
        return new ComposeModel("docker compose config", cast(JSON.treeToValue(config, Map.class)));
    }

    String origin() {
        return origin;
    }

    Map<String, Object> services() {
        return map(root.get("services"));
    }

    Map<String, Object> service(String name) {
        Object s = services().get(name);
        if (s == null) {
            throw new AssertionError(origin + " has no service " + name + "; services: " + services().keySet());
        }
        return map(s);
    }

    /** Top-level volumes. */
    Map<String, Object> volumes() {
        return map(root.get("volumes"));
    }

    String image(String service) {
        Object image = service(service).get("image");
        return image == null ? null : image.toString();
    }

    List<Port> ports(String service) {
        List<Port> out = new ArrayList<>();
        for (Object p : list(service(service).get("ports"))) {
            if (p instanceof Map<?, ?> m) {
                Object hostIp = m.get("host_ip");
                Object published = m.get("published");
                out.add(new Port(hostIp == null ? null : hostIp.toString(),
                        published == null ? null : published.toString(),
                        Integer.parseInt(m.get("target").toString())));
            } else {
                out.add(shortPort(p.toString()));
            }
        }
        return out;
    }

    List<Mount> mounts(String service) {
        List<Mount> out = new ArrayList<>();
        for (Object v : list(service(service).get("volumes"))) {
            if (v instanceof Map<?, ?> m) {
                out.add(new Mount(String.valueOf(m.get("type")), String.valueOf(m.get("source")),
                        String.valueOf(m.get("target"))));
            } else {
                String[] parts = v.toString().split(":");
                if (parts.length >= 2) {
                    out.add(new Mount("volume", parts[0], parts[1]));
                } else {
                    out.add(new Mount("volume", null, parts[0]));
                }
            }
        }
        return out;
    }

    Map<String, Object> environment(String service) {
        Object env = service(service).get("environment");
        Map<String, Object> out = new LinkedHashMap<>();
        if (env instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(k.toString(), v));
        } else {
            for (Object e : list(env)) {
                String[] kv = e.toString().split("=", 2);
                out.put(kv[0], kv.length > 1 ? kv[1] : null);
            }
        }
        return out;
    }

    private static Port shortPort(String spec) {
        String s = spec.replaceAll("/(tcp|udp)$", "");
        String[] parts = s.split(":");
        return switch (parts.length) {
            case 1 -> new Port(null, null, Integer.parseInt(parts[0]));
            case 2 -> new Port(null, parts[0], Integer.parseInt(parts[1]));
            default -> new Port(parts[0], parts[1], Integer.parseInt(parts[2]));
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    static Map<String, Object> map(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> m)) {
            throw new AssertionError("Expected a mapping but got " + value);
        }
        return cast(m);
    }

    static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> l)) {
            throw new AssertionError("Expected a list but got " + value);
        }
        return l;
    }
}
