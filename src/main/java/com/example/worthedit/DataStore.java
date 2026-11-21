package com.example.worthedit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Small persistent store: locked items, baseline prices, sales pressure, dynamic on/off. */
final class DataStore {
    private final WorthEditor plugin;
    private final File file;
    boolean dynamic;
    final Set<String> locks = new HashSet<>();
    final Map<String, Double> baseline = new HashMap<>();
    final Map<String, Double> pressure = new HashMap<>();

    DataStore(WorthEditor plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        locks.clear();
        baseline.clear();
        pressure.clear();
        dynamic = plugin.getConfig().getBoolean("dynamic.enabled", false);
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.contains("dynamic")) dynamic = y.getBoolean("dynamic");
        locks.addAll(y.getStringList("locks"));
        readMap(y.getConfigurationSection("baseline"), baseline);
        readMap(y.getConfigurationSection("pressure"), pressure);
    }

    private static void readMap(ConfigurationSection s, Map<String, Double> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k, s.getDouble(k));
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("dynamic", dynamic);
        y.set("locks", new ArrayList<>(locks));
        baseline.forEach((k, v) -> y.set("baseline." + k, v));
        pressure.forEach((k, v) -> {
            if (v > 0.01) y.set("pressure." + k, v);
        });
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    boolean isLocked(String key) {
        return locks.contains(key);
    }

    void addPressure(String material, double amount) {
        pressure.merge(material, amount, Double::sum);
    }
}
