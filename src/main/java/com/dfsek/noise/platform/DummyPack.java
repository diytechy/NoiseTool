/*
 * This file is part of Terra.
 *
 * Terra is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Terra is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Terra.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.dfsek.noise.platform;

import ca.solostudios.strata.Versions;
import ca.solostudios.strata.version.Version;
import ca.solostudios.strata.version.VersionRange;
import com.dfsek.paralithic.eval.parser.Parser.ParseOptions;
import com.dfsek.tectonic.api.TypeRegistry;
import com.dfsek.tectonic.api.config.Configuration;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;
import com.dfsek.tectonic.api.loader.AbstractConfigLoader;
import com.dfsek.tectonic.api.loader.ConfigLoader;
import com.dfsek.tectonic.api.loader.type.TypeLoader;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.config.ConfigType;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPostLoadEvent;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.terra.api.properties.Context;
import com.dfsek.terra.api.registry.CheckedRegistry;
import com.dfsek.terra.api.registry.OpenRegistry;
import com.dfsek.terra.api.registry.Registry;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.tectonic.ShortcutLoader;
import com.dfsek.terra.api.util.reflection.ReflectionUtil;
import com.dfsek.terra.api.util.reflection.TypeKey;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.stage.GenerationStage;
import com.dfsek.terra.api.world.chunk.generation.util.provider.ChunkGeneratorProvider;
import com.dfsek.terra.config.loaders.GenericTemplateSupplierLoader;
import com.dfsek.terra.registry.CheckedRegistryImpl;
import com.dfsek.terra.registry.OpenRegistryImpl;
import com.dfsek.terra.registry.ShortcutHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dfsek.noise.config.HighAliasYamlConfiguration;
import com.dfsek.terra.api.properties.Properties;
import com.dfsek.tectonic.api.config.template.ConfigTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;


/**
 * Represents a Terra configuration pack for noise tool.
 */
public class DummyPack implements ConfigPack {
    private final Context context = new Context();
    private static final Logger logger = LoggerFactory.getLogger(DummyPack.class);

    private final AbstractConfigLoader abstractConfigLoader = new AbstractConfigLoader();
    private final ConfigLoader selfLoader = new ConfigLoader();

    private final Map<BaseAddon, VersionRange> addons;

    private final Map<Type, CheckedRegistryImpl<?>> registryMap = new HashMap<>();
    private final Map<Type, ShortcutHolder<?>> shortcuts = new HashMap<>();

    private final RegistryKey key;

    private final Sampler noiseSampler;

    private final boolean useLetExpressions;

    @SuppressWarnings("unchecked")
    public DummyPack(Platform platform, Configuration noise, boolean useLetExpressions) {
        this.useLetExpressions = useLetExpressions;

        register(selfLoader);
        platform.register(selfLoader);

        register(abstractConfigLoader);
        platform.register(abstractConfigLoader);


        this.addons = platform
                .getAddons()
                .entries()
                .stream()
                .collect(HashMap::new, (map, addon) -> map.put(addon, Versions.getVersionRange(addon.getVersion(), true, addon.getVersion(), true)), HashMap::putAll);

        // Extract samplers from config for sequential loading
        Map<String, Object> samplerConfigs = null;
        Configuration strippedNoise = noise;
        if (noise.contains("samplers")) {
            Object samplersObj = noise.get("samplers");
            if (samplersObj instanceof Map) {
                samplerConfigs = (Map<String, Object>) samplersObj;
                // Create a config without samplers so NoiseAddon loads an empty template
                Map<String, Object> strippedMap = new LinkedHashMap<>();
                // Copy all keys except samplers
                for (String key : List.of("type", "expression", "dimensions", "variables", "functions",
                        "sampler", "frequency", "salt", "amplitude", "octaves")) {
                    if (noise.contains(key)) {
                        strippedMap.put(key, noise.get(key));
                    }
                }
                strippedMap.put("samplers", new LinkedHashMap<>()); // empty samplers
                strippedNoise = new HighAliasYamlConfiguration(strippedMap, noise.getName());
            }
        }

        // Fire event with stripped config — types get registered, packSamplers created empty
        final Configuration eventConfig = strippedNoise;
        platform.getEventManager().callEvent(
                new ConfigPackPreLoadEvent(this, template -> selfLoader.load(template, eventConfig)));

        // Sequential sampler loading: load each in dependency order.
        // Uses reflection to access addon classes (they live in a child classloader).
        if (samplerConfigs != null && !samplerConfigs.isEmpty()) {
            Properties packCtx = context.getByClassName("com.dfsek.terra.addons.noise.PackSamplerContext");
            if (packCtx != null) {
                try {
                    Method getSamplers = packCtx.getClass().getMethod("getSamplers");
                    Method getFunctions = packCtx.getClass().getMethod("getFunctions");
                    Map<String, Object> samplerMap = (Map<String, Object>) getSamplers.invoke(packCtx);
                    Map<String, Object> functionMap = (Map<String, Object>) getFunctions.invoke(packCtx);

                    // Use the addon classloader to create NoiseConfigPackTemplate instances
                    Class<?> templateClass = packCtx.getClass().getClassLoader()
                            .loadClass("com.dfsek.terra.addons.noise.NoiseConfigPackTemplate");
                    Method getTemplateSamplers = templateClass.getMethod("getSamplers");
                    Method getTemplateFunctions = templateClass.getMethod("getFunctions");

                    // Load functions first (no inter-dependencies)
                    if (noise.contains("functions")) {
                        Map<String, Object> funcMap = new LinkedHashMap<>();
                        funcMap.put("functions", noise.get("functions"));
                        Object funcTemplate = templateClass.getDeclaredConstructor().newInstance();
                        selfLoader.load((ConfigTemplate) funcTemplate, new HighAliasYamlConfiguration(funcMap, "Functions"));
                        functionMap.putAll((Map<String, Object>) getTemplateFunctions.invoke(funcTemplate));
                    }

                    List<String> ordered = topologicalSort(samplerConfigs);
                    logger.info("Loading {} pack samplers in dependency order", ordered.size());
                    for (String name : ordered) {
                        Map<String, Object> singleSampler = new LinkedHashMap<>();
                        singleSampler.put(name, samplerConfigs.get(name));
                        Map<String, Object> miniMap = new LinkedHashMap<>();
                        miniMap.put("samplers", singleSampler);
                        Object miniTemplate = templateClass.getDeclaredConstructor().newInstance();
                        selfLoader.load((ConfigTemplate) miniTemplate, new HighAliasYamlConfiguration(miniMap, "Sampler: " + name));
                        samplerMap.putAll((Map<String, Object>) getTemplateSamplers.invoke(miniTemplate));
                    }
                    logger.info("All pack samplers loaded successfully");
                } catch (Exception e) {
                    logger.error("Sequential sampler loading failed", e);
                }
            }
        }

        this.key = RegistryKey.of("noise", "noise");

        platform.getEventManager().callEvent(new ConfigPackPostLoadEvent(this, template -> selfLoader.load(template, noise)));

        ObjectTemplate<Sampler> noiseSamplerObjectTemplate = new ObjectTemplate<>() {
            @Value(".")
            private Sampler value;

            @Override
            public Sampler get() {
                return value;
            }
        };

        this.noiseSampler = selfLoader.load(noiseSamplerObjectTemplate, noise).get();
    }

    /**
     * Topologically sort sampler names by dependency (dependencies first).
     * Scans expression text for references to other known sampler names.
     */
    @SuppressWarnings("unchecked")
    private static List<String> topologicalSort(Map<String, Object> samplerConfigs) {
        Set<String> knownNames = samplerConfigs.keySet();

        // Build dependency graph
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (String name : knownNames) {
            deps.put(name, new HashSet<>());
            Object config = samplerConfigs.get(name);
            if (config != null) {
                String configText = config.toString();
                for (String candidate : knownNames) {
                    if (!candidate.equals(name) &&
                        (configText.contains(candidate + "(") || configText.contains(candidate + " ("))) {
                        deps.get(name).add(candidate);
                    }
                }
            }
        }

        // Topological sort (Kahn's algorithm)
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : knownNames) {
            visit(name, deps, visited, visiting, result);
        }

        return result;
    }

    private static void visit(String name, Map<String, Set<String>> deps,
                              Set<String> visited, Set<String> visiting, List<String> result) {
        if (visited.contains(name)) return;
        if (visiting.contains(name)) return; // cycle — skip
        visiting.add(name);
        for (String dep : deps.getOrDefault(name, Set.of())) {
            visit(dep, deps, visited, visiting, result);
        }
        visiting.remove(name);
        visited.add(name);
        result.add(name);
    }

    public Sampler getSampler() {
        return noiseSampler;
    }

    @Override
    public <T> DummyPack applyLoader(Type type, TypeLoader<T> loader) {
        abstractConfigLoader.registerLoader(type, loader);
        selfLoader.registerLoader(type, loader);
        return this;
    }

    @Override
    public <T> DummyPack applyLoader(Type type, Supplier<ObjectTemplate<T>> loader) {
        abstractConfigLoader.registerLoader(type, loader);
        selfLoader.registerLoader(type, loader);
        return this;
    }

    @Override
    public void register(TypeRegistry registry) {
        registryMap.forEach(registry::registerLoader);
        shortcuts.forEach(registry::registerLoader);
    }

    @Override
    public ConfigPack registerConfigType(ConfigType<?, ?> type, RegistryKey key, int priority) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<BaseAddon, VersionRange> addons() {
        return addons;
    }

    @Override
    public BiomeProvider getBiomeProvider() {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> CheckedRegistry<T> getOrCreateRegistry(TypeKey<T> typeKey) {
        return (CheckedRegistry<T>) registryMap.computeIfAbsent(typeKey.getType(), c -> {
            OpenRegistry<T> registry = new OpenRegistryImpl<>(typeKey);
            selfLoader.registerLoader(c, registry);
            abstractConfigLoader.registerLoader(c, registry);
            logger.debug("Registered loader for registry of class {}", ReflectionUtil.typeToString(c));

            if (typeKey.getType() instanceof ParameterizedType param) {
                Type base = param.getRawType();
                if (base instanceof Class
                        && Supplier.class.isAssignableFrom((Class<?>) base)) {
                    Type supplied = param.getActualTypeArguments()[0];
                    if (supplied instanceof ParameterizedType suppliedParam) {
                        Type suppliedBase = suppliedParam.getRawType();
                        if (suppliedBase instanceof Class
                                && ObjectTemplate.class.isAssignableFrom((Class<?>) suppliedBase)) {
                            Type templateType = suppliedParam.getActualTypeArguments()[0];
                            GenericTemplateSupplierLoader<?> loader = new GenericTemplateSupplierLoader<>(
                                    (Registry<Supplier<ObjectTemplate<Supplier<ObjectTemplate<?>>>>>) registry);
                            selfLoader.registerLoader(templateType, loader);
                            abstractConfigLoader.registerLoader(templateType, loader);
                            logger.debug("Registered template loader for registry of class {}", ReflectionUtil.typeToString(templateType));
                        }
                    }
                }
            }

            return new CheckedRegistryImpl<>(registry);
        });
    }

    @Override
    public List<GenerationStage> getStages() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path getRootPath() {
        return Path.of(".");
    }

    @Override
    public String getAuthor() {
        return "0.1.0";
    }

    @Override
    public Version getVersion() {
        return Versions.getVersion(1, 0, 0);
    }

    @Override
    public ParseOptions getExpressionParseOptions() {
        return new ParseOptions(useLetExpressions);
    }

    @SuppressWarnings("unchecked,rawtypes")
    @Override
    public <T> ConfigPack registerShortcut(TypeKey<T> clazz, String shortcut, ShortcutLoader<T> loader) {
        ShortcutHolder<?> holder = shortcuts
                .computeIfAbsent(clazz.getType(), c -> new ShortcutHolder<>(getOrCreateRegistry(clazz)))
                .register(shortcut, (ShortcutLoader) loader);
        selfLoader.registerLoader(clazz.getType(), holder);
        abstractConfigLoader.registerLoader(clazz.getType(), holder);
        return this;
    }

    @Override
    public ChunkGeneratorProvider getGeneratorProvider() {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CheckedRegistry<T> getRegistry(Type type) {
        return (CheckedRegistry<T>) registryMap.get(type);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> CheckedRegistry<T> getCheckedRegistry(Type type) throws IllegalStateException {
        return (CheckedRegistry<T>) registryMap.get(type);
    }

    @Override
    public RegistryKey getRegistryKey() {
        return key;
    }

    @Override
    public Context getContext() {
        return context;
    }
}
