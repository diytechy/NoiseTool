package com.dfsek.noise.config;

import com.dfsek.tectonic.api.config.Configuration;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/**
 * YamlConfiguration replacement that increases the SnakeYAML max aliases limit
 * from the default of 50 to 500, allowing complex YAML with many anchors/aliases.
 */
public class HighAliasYamlConfiguration implements Configuration {
    private static final int MAX_ALIASES = 500;

    private final Object config;
    private final String name;

    public HighAliasYamlConfiguration(String yaml, String name) {
        this.name = name;
        this.config = createYaml().load(yaml);
    }

    public HighAliasYamlConfiguration(Map<String, Object> config, String name) {
        this.name = name;
        this.config = config;
    }

    private static Yaml createYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setCodePointLimit(16 * 1024 * 1024); // 16 MB (default is 3 MB)
        return new Yaml(options);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object get(@NotNull String key) {
        String[] levels = key.split("\\.");
        Object level = config;
        for (String keyLevel : levels) {
            if (!(level instanceof Map)) throw new IllegalArgumentException();
            level = ((Map<String, Object>) level).get(keyLevel);
        }
        return level;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(@NotNull String key) {
        String[] levels = key.split("\\.");
        Object level = config;
        for (String keyLevel : levels) {
            if (!(level instanceof Map)) return false;
            level = ((Map<String, Object>) level).get(keyLevel);
        }
        return !(level == null);
    }

    @Override
    public String getName() {
        return name;
    }
}
