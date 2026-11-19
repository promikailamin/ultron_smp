package minecraft.heads;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.EntityType;

/**
 * Loads the bundled mob-head texture database and resolves it against the
 * server's EntityType registry. Every living entity gets an entry, using a
 * small set of aliases for entities whose texture keys changed over time.
 */
public final class HeadTextures {

    private static final Map<String, String> ALIASES = Map.of(
            "ZOMBIFIED_PIGLIN", "PIG_ZOMBIE",
            "MOOSHROOM", "MUSHROOM_COW",
            "SNOW_GOLEM", "SNOWMAN",
            "MANNEQUIN", "ARMOR_STAND");

    private final Map<String, String> values;
    private final Map<EntityType, String> byType = new EnumMap<>(EntityType.class);
    private final List<EntityType> types = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();

    private HeadTextures(Map<String, String> values) {
        this.values = values;
    }

    public static HeadTextures load(InputStream stream) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab + 1 >= line.length()) {
                    continue;
                }
                String key = line.substring(0, tab).trim().toUpperCase(Locale.ROOT);
                String value = line.substring(tab + 1).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    values.put(key, value);
                }
            }
        }

        HeadTextures textures = new HeadTextures(values);
        textures.resolve();
        return textures;
    }

    private void resolve() {
        String fallback = values.get("UNKNOWN");
        for (EntityType type : EntityType.values()) {
            if (type == EntityType.PLAYER || type == EntityType.UNKNOWN || !type.isAlive()) {
                continue;
            }
            String key = type.getKey().getKey().toUpperCase(Locale.ROOT);
            String value = values.get(key);
            if (value == null) {
                String alias = ALIASES.get(key);
                if (alias != null) {
                    value = values.get(alias);
                }
            }
            if (value == null) {
                value = fallback;
                missing.add(key);
            }
            if (value != null) {
                byType.put(type, value);
                types.add(type);
            }
        }
        Collections.sort(types, (a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));
    }

    public String textureFor(EntityType type) {
        return byType.get(type);
    }

    public boolean has(EntityType type) {
        return byType.containsKey(type);
    }

    public List<EntityType> types() {
        return Collections.unmodifiableList(types);
    }

    public List<String> missing() {
        return Collections.unmodifiableList(missing);
    }

    public int size() {
        return types.size();
    }

    public static String displayName(EntityType type) {
        String raw = type.getKey().getKey().replace('_', ' ');
        StringBuilder result = new StringBuilder(raw.length());
        boolean upper = true;
        for (char c : raw.toCharArray()) {
            if (c == ' ') {
                result.append(c);
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(c));
                upper = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
