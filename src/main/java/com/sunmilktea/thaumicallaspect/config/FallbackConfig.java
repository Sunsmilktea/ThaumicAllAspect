package com.sunmilktea.thaumicallaspect.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.AspectList;

/**
 * Loads user-defined fallbacks from config/ThaumicAllAspect/:
 * - keyword-fallback.cfg: keyword=aspect1=amt1,aspect2=amt2 (name contains keyword → add aspects)
 * - item-fallback.cfg: modid:name@meta=aspect1=amt1,... or fluid:modid:name=... (exact key → use aspects)
 */
public final class FallbackConfig {

    private static final String CONFIG_SUBDIR = "config/ThaumicAllAspect";

    /** Keyword → AspectList. If item name contains keyword, add these aspects in createGeneralFallback. */
    static Map<String, AspectList> keywordFallback = new HashMap<>();
    /** Key (registryName@meta or fluid:modid:name) → AspectList. Direct assignment before derivation. */
    static Map<String, AspectList> itemFallback = new HashMap<>();

    /**
     * Loads both config files. Call once at scan start (when no cache or when building indices).
     * Creates template files with comments if missing.
     */
    public static void load(File configDir) {
        keywordFallback.clear();
        itemFallback.clear();
        if (configDir == null) configDir = new File(CONFIG_SUBDIR);
        File kwFile = new File(configDir, "keyword-fallback.cfg");
        File itemFile = new File(configDir, "item-fallback.cfg");
        ensureKeywordFallbackExists(kwFile);
        ensureItemFallbackExists(itemFile);
        loadKeywordFallback(kwFile);
        loadItemFallback(itemFile);
    }

    private static void ensureKeywordFallbackExists(File file) {
        if (file.exists()) return;
        try {
            file.getParentFile()
                .mkdirs();
            try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                w.write("# ThaumicAllAspect keyword fallback. One line: keyword=aspect1=amount1,aspect2=amount2");
                w.newLine();
                w.write("# If item name contains the keyword, these aspects are added. Example:");
                w.newLine();
                w.write("# mykeyword=terra=4,perditio=2");
                w.newLine();
            }
        } catch (IOException ignored) {}
    }

    private static void ensureItemFallbackExists(File file) {
        if (file.exists()) return;
        try {
            file.getParentFile()
                .mkdirs();
            try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                w.write("# ThaumicAllAspect item/block/fluid fallback. Exact key = aspects.");
                w.newLine();
                w.write("# Item/block: modid:name@meta=aspect1=amt1,...  Fluid: fluid:fluidname=aspect1=amt1,...");
                w.newLine();
                w.write("# Example: minecraft:diamond@0=vitreus=4,lucrum=2");
                w.newLine();
            }
        } catch (IOException ignored) {}
    }

    /** Returns a copy of the aspect list for this key if in item-fallback config, else null. */
    public static AspectList getItemFallback(String key) {
        AspectList al = itemFallback.get(key);
        return al != null ? al.copy() : null;
    }

    /** Returns a copy of the aspect list for this fluid key (fluid:modid:name) if in config, else null. */
    public static AspectList getFluidFallback(String fluidKey) {
        AspectList al = itemFallback.get(fluidKey);
        return al != null ? al.copy() : null;
    }

    /** Applies all item/fluid fallbacks: register to TC and put in CACHE so rest of scan skips derivation. */
    public static void applyItemFallbacksToCache() {
        for (Map.Entry<String, AspectList> e : itemFallback.entrySet()) {
            String key = e.getKey();
            AspectList al = e.getValue();
            if (al == null || al.size() == 0) continue;
            if (key.startsWith("fluid:")) {
                String fluidId = key.substring(6);
                Fluid fluid = FluidRegistry.getFluid(fluidId);
                if (fluid != null) {
                    ItemStack rep = AspectUtils.getFluidRepresentative(fluid);
                    if (rep != null) {
                        String repKey = AspectUtils.key(rep);
                        ThaumcraftApi.registerObjectTag(rep, al.copy());
                        AspectUtils.CACHE.put(repKey, al.copy());
                    }
                }
                continue;
            }
            int at = key.indexOf('@');
            if (at <= 0) continue;
            String regName = key.substring(0, at);
            int meta;
            try {
                meta = Integer.parseInt(key.substring(at + 1));
            } catch (NumberFormatException ex) {
                continue;
            }
            Object itemObj = Item.itemRegistry.getObject(regName);
            if (!(itemObj instanceof Item)) continue;
            ItemStack stack = new ItemStack((Item) itemObj, 1, meta);
            ThaumcraftApi.registerObjectTag(stack, al.copy());
            AspectUtils.CACHE.put(key, al.copy());
        }
    }

    /** Checks config keyword fallbacks: if combined name contains keyword, add that AspectList to fb. */
    public static void applyKeywordFallbacks(String combinedName, AspectList fb) {
        for (Map.Entry<String, AspectList> e : keywordFallback.entrySet()) {
            if (combinedName.contains(e.getKey())) {
                AspectList add = e.getValue();
                if (add != null) fb.add(add);
            }
        }
    }

    private static void loadKeywordFallback(File file) {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String keyword = line.substring(0, eq)
                    .trim();
                String val = line.substring(eq + 1)
                    .trim();
                AspectList al = AspectUtils.parseAspectListPublic(val);
                if (al != null && al.size() > 0) keywordFallback.put(keyword, al);
            }
        } catch (IOException ex) {
            ModFileLogger.warn("[ThaumicAllAspect] Error reading keyword-fallback.cfg: " + ex.getMessage());
        }
    }

    private static void loadItemFallback(File file) {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq)
                    .trim();
                String val = line.substring(eq + 1)
                    .trim();
                AspectList al = AspectUtils.parseAspectListPublic(val);
                if (al != null && al.size() > 0) itemFallback.put(key, al);
            }
        } catch (IOException ex) {
            ModFileLogger.warn("[ThaumicAllAspect] Error reading item-fallback.cfg: " + ex.getMessage());
        }
    }
}
