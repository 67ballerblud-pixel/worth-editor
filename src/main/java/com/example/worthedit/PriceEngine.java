package com.example.worthedit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

import java.util.*;

/**
 * Computes item values.
 *
 * Base items use the raw-values table in config.yml. Crafted and smelted items
 * are derived from the server's recipes (cheapest recipe wins), so selling a
 * crafted item can never beat selling its ingredients. Items with neither a raw
 * value nor a recipe keep their baseline. In dynamic mode a supply/demand
 * multiplier is applied on top.
 */
final class PriceEngine {
    private final WorthEditor plugin;
    private final Map<Material, Double> raw = new HashMap<>();
    private final Map<Material, Double> memo = new HashMap<>();
    private Map<Material, List<Recipe>> index;
    private Map<Material, Long> inventoryCounts = Map.of();

    PriceEngine(WorthEditor plugin) {
        this.plugin = plugin;
    }

    void reloadConfig() {
        raw.clear();
        index = null;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("raw-values");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null) {
                plugin.getLogger().warning("Unknown material in raw-values: " + k);
                continue;
            }
            raw.put(m, s.getDouble(k));
        }
    }

    // ---------------------------------------------------------------- compute

    /** Returns (category + NUL + key) -> new price for every item that can be calculated. */
    Map<String, Double> compute(boolean dynamic) {
        memo.clear();
        Map<String, Double> out = new LinkedHashMap<>();
        WorthFile worth = plugin.worth;
        DataStore store = plugin.store;

        // remember the first price we ever saw so repeated runs never compound
        for (Map<String, Double> map : worth.data.values()) {
            for (Map.Entry<String, Double> e : map.entrySet()) {
                if (e.getKey().indexOf(':') >= 0) continue;
                store.baseline.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        for (Map.Entry<String, LinkedHashMap<String, Double>> cat : worth.data.entrySet()) {
            for (String key : cat.getValue().keySet()) {
                if (key.indexOf(':') >= 0) continue; // enchant/potion variants are left alone
                if (store.isLocked(key)) continue;
                Material m = Material.matchMaterial(key);
                if (m == null) continue;
                Double base = value(m, new HashSet<>());
                if (base == null) continue;
                double v = base * (dynamic ? multiplier(m) : 1.0);
                out.put(cat.getKey() + "\0" + key, round(v));
            }
        }
        return out;
    }

    private Double value(Material m, Set<Material> stack) {
        Double cached = memo.get(m);
        if (cached != null) return cached;
        Double r = raw.get(m);
        if (r != null) {
            memo.put(m, r);
            return r;
        }
        if (!stack.add(m)) return null; // recipe cycle
        Double best = null;
        List<Recipe> recipes = index().get(m);
        if (recipes != null) {
            for (Recipe rec : recipes) {
                Double c = cost(rec, stack);
                if (c != null && (best == null || c < best)) best = c;
            }
        }
        stack.remove(m);
        if (best == null) {
            best = plugin.store.baseline.get(m.name());
            if (best == null) best = plugin.worth.firstPrice(m);
        }
        if (best != null) memo.put(m, best);
        return best;
    }

    private Double cost(Recipe r, Set<Material> stack) {
        List<RecipeChoice> ings = new ArrayList<>();
        double factor;
        if (r instanceof ShapedRecipe s) {
            Map<Character, RecipeChoice> cm = s.getChoiceMap();
            for (String row : s.getShape()) {
                for (char c : row.toCharArray()) {
                    if (c == ' ') continue;
                    RecipeChoice rc = cm.get(c);
                    if (rc != null) ings.add(rc);
                }
            }
            factor = plugin.getConfig().getDouble("engine.craft-factor", 1.0);
        } else if (r instanceof ShapelessRecipe sl) {
            ings.addAll(sl.getChoiceList());
            factor = plugin.getConfig().getDouble("engine.craft-factor", 1.0);
        } else if (r instanceof CookingRecipe<?> c) {
            ings.add(c.getInputChoice());
            factor = plugin.getConfig().getDouble("engine.smelt-factor", 1.1);
        } else {
            return null;
        }
        double total = 0;
        for (RecipeChoice rc : ings) {
            Double p = choicePrice(rc, stack);
            if (p == null) return null;
            total += p;
        }
        int out = Math.max(1, r.getResult().getAmount());
        return total * factor / out;
    }

    private Double choicePrice(RecipeChoice rc, Set<Material> stack) {
        if (rc instanceof RecipeChoice.MaterialChoice mc) {
            Double best = null;
            for (Material m : mc.getChoices()) {
                Double p = value(m, stack);
                if (p != null && (best == null || p < best)) best = p;
            }
            return best;
        }
        if (rc instanceof RecipeChoice.ExactChoice ec) {
            Double best = null;
            for (ItemStack is : ec.getChoices()) {
                Double p = value(is.getType(), stack);
                if (p != null && (best == null || p < best)) best = p;
            }
            return best;
        }
        return null;
    }

    private Map<Material, List<Recipe>> index() {
        if (index == null) {
            index = new HashMap<>();
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe r = it.next();
                if (r instanceof ShapedRecipe || r instanceof ShapelessRecipe || r instanceof CookingRecipe<?>) {
                    index.computeIfAbsent(r.getResult().getType(), k -> new ArrayList<>()).add(r);
                }
            }
        }
        return index;
    }

    // ---------------------------------------------------------------- dynamic

    double multiplier(Material m) {
        var cfg = plugin.getConfig();
        double sales = plugin.store.pressure.getOrDefault(m.name(), 0.0);
        double held = inventoryCounts.getOrDefault(m, 0L) * cfg.getDouble("dynamic.inventory-weight", 0.05);
        double pressure = sales + held;
        double v = cfg.getDouble("dynamic.base", 1.25) - cfg.getDouble("dynamic.slope", 0.15) * Math.log10(1 + pressure);
        return Math.max(cfg.getDouble("dynamic.min", 0.6), Math.min(cfg.getDouble("dynamic.max", 1.5), v));
    }

    private void snapshotInventories() {
        Map<Material, Long> counts = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack is : p.getInventory().getContents()) add(counts, is);
            for (ItemStack is : p.getEnderChest().getContents()) add(counts, is);
        }
        inventoryCounts = counts;
    }

    private static void add(Map<Material, Long> counts, ItemStack is) {
        if (is == null || is.getType().isAir()) return;
        counts.merge(is.getType(), (long) is.getAmount(), Long::sum);
    }

    /** One dynamic cycle: recompute, stage, save, reload the shop, decay sales pressure. */
    void runDynamic() {
        snapshotInventories();
        Map<String, Double> prices = compute(true);
        int changed = 0;
        for (Map.Entry<String, Double> e : prices.entrySet()) {
            int split = e.getKey().indexOf('\0');
            if (plugin.worth.set(e.getKey().substring(0, split), e.getKey().substring(split + 1), e.getValue())) changed++;
        }
        plugin.saveWorth();
        double decay = plugin.getConfig().getDouble("dynamic.sales-decay", 0.8);
        plugin.store.pressure.replaceAll((k, v) -> v * decay);
        plugin.store.save();
        plugin.getLogger().info("Dynamic pricing updated " + changed + " items.");
        if (plugin.getConfig().getBoolean("dynamic.broadcast", false)) {
            Bukkit.broadcast(net.kyori.adventure.text.Component.text("Shop prices have been updated (" + changed + " items)."));
        }
    }

    /** Stages the non-dynamic auto-balanced prices (needs a Save afterwards). */
    int stageAutoBalance() {
        int n = 0;
        for (Map.Entry<String, Double> e : compute(false).entrySet()) {
            int split = e.getKey().indexOf('\0');
            if (plugin.worth.set(e.getKey().substring(0, split), e.getKey().substring(split + 1), e.getValue())) n++;
        }
        return n;
    }

    static double round(double v) {
        if (v >= 1000) return Math.round(v);
        return Math.round(v * 100.0) / 100.0;
    }
}
