package com.sunmilktea.thaumicallaspect.aspect;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.IntSupplier;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.sunmilktea.thaumicallaspect.ThaumicAllAspect;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.ThaumcraftApiHelper;
import thaumcraft.api.aspects.AspectList;

/**
 * Scans non-standard recipe systems from other mods via reflection, extracting input/output
 * pairs to derive aspects for items that the main CraftingManager-based scanner missed.
 *
 * Each mod's recipe system is accessed purely through reflection so there is no hard dependency.
 * If a mod isn't loaded, its scanner method silently returns zero results.
 * All reflection failures are logged to the scan log file for debugging.
 *
 * Currently supported mods:
 * <ul>
 * <li>AbyssalCraft — Necronomicon Rituals (Creation &amp; Infusion), Transmutator,
 * Crystallizer, Materializer</li>
 * <li>Witchery (巫术) — Kettle, Distillery, Spinning Wheel, Rite rituals</li>
 * <li>Blood Magic (血魔法) — Blood Altar, Alchemy Table, Binding rituals</li>
 * <li>Botania (植物魔法) — Mana Infusion, Runic Altar, Petal Apothecary, Elven Trade</li>
 * <li>Forestry (林业) — Carpenter, Centrifuge, Squeezer, Fermenter, Still, Moistener</li>
 * <li>Tinkers' Construct (匠魂) — Smeltery, Casting Table/Basin</li>
 * <li>EnderIO — Alloy Smelter, SAG Mill, Enchanter</li>
 * <li>Railcraft — Rolling Machine, Blast Furnace, Rock Crusher, Coke Oven</li>
 * </ul>
 *
 * 通过反射扫描其他模组的非标准配方系统，提取输入/输出对，
 * 为主 CraftingManager 扫描器遗漏的物品推导要素。
 *
 * 每个模组的配方系统完全通过反射访问，因此没有硬依赖。
 * 如果模组未加载，其扫描方法会静默返回零结果。
 * 所有反射失败都会记录到扫描日志文件以便调试。
 *
 * 当前支持的模组：
 * <ul>
 * <li>深渊国度 (AbyssalCraft) — 死灵之书仪式（创造 &amp; 注入）、变质器、结晶器、物化器</li>
 * <li>巫术 (Witchery) — 大釜、蒸馏器、纺车、仪式</li>
 * <li>血魔法 (Blood Magic) — 血祭坛、炼金术台、绑定仪式</li>
 * <li>植物魔法 (Botania) — 魔力注入、符文祭坛、花瓣炼药、精灵贸易</li>
 * <li>林业 (Forestry) — 木工机、离心机、榨汁机、发酵机、蒸馏器、湿润器</li>
 * <li>匠魂 (Tinkers' Construct) — 冶炼炉、浇铸台/浇铸盆</li>
 * <li>末影接口 (EnderIO) — 合金冶炼炉、SAG磨粉机、附魔器</li>
 * <li>铁路 (Railcraft) — 轧制机、高炉、碎石机、焦炉</li>
 * </ul>
 */
public final class ModRecipeBridge {

    private ModRecipeBridge() {}

    /** Total items registered by all mod recipe bridges in a single scan. */
    static int statModRecipeRegistered = 0;

    /**
     * Runs one mod's scanner; on Throwable (e.g. NoClassDefFoundError from client-only class)
     * logs and returns 0 so server can still run other mods' scans.
     * 执行单个模组的扫描；若抛出 Throwable（如因客户端类导致 NoClassDefFoundError）则记录并返回 0，服务器可继续扫描其他模组。
     */
    private static int safeModScan(String modName, IntSupplier scan) {
        try {
            return scan.getAsInt();
        } catch (NoClassDefFoundError e) {
            ModFileLogger.warn(
                "[ThaumicAllAspect] " + modName
                    + " "
                    + tr("scan skipped")
                    + " (incompatible/missing class): "
                    + (e.getMessage() != null ? e.getMessage()
                        : e.getClass()
                            .getSimpleName()));
            return 0;
        } catch (Throwable t) {
            String msg = t.getClass()
                .getSimpleName();
            if (t.getMessage() != null) msg += ": " + t.getMessage();
            ModFileLogger.warn("[ThaumicAllAspect] " + modName + " " + tr("scan skipped") + ": " + msg);
            return 0;
        }
    }

    // ==================== Entry Point / 入口 ====================

    /**
     * Entry point: runs all supported mod recipe scanners with multi-pass retry.
     *
     * Recipe outputs may depend on other mod-recipe outputs as inputs.
     * A single pass can miss items whose ingredients haven't been derived yet.
     * So we repeat the full scan until no new items are registered (convergence)
     * or the maximum number of passes is reached.
     *
     * 入口：带多轮重试运行所有支持的模组配方扫描器。
     *
     * 配方产物可能依赖其他模组配方的产物作为材料。
     * 单次扫描可能遗漏材料尚未推导的物品。
     * 因此重复完整扫描直到无新注册（收敛）或达到最大轮次。
     */
    public static void scanModSpecificRecipes() {
        if (FMLCommonHandler.instance()
            .getEffectiveSide() == Side.SERVER) {
            return; // 服务器依赖 config 缓存获得模组配方要素，不执行任何反射扫描
        }
        statModRecipeRegistered = 0;
        long t0 = System.currentTimeMillis();
        int maxPasses = 5;
        int totalCount = 0;

        for (int pass = 1; pass <= maxPasses; pass++) {
            int passCount = 0;
            if (Loader.isModLoaded("abyssalcraft"))
                passCount += safeModScan("AbyssalCraft", ModRecipeBridge::scanAbyssalCraft);
            if (Loader.isModLoaded("witchery")) passCount += safeModScan("Witchery", ModRecipeBridge::scanWitchery);
            if (Loader.isModLoaded("bloodmagic"))
                passCount += safeModScan("Blood Magic", ModRecipeBridge::scanBloodMagic);
            if (Loader.isModLoaded("botania")) passCount += safeModScan("Botania", ModRecipeBridge::scanBotania);
            if (Loader.isModLoaded("forestry")) passCount += safeModScan("Forestry", ModRecipeBridge::scanForestry);
            if (Loader.isModLoaded("tconstruct"))
                passCount += safeModScan("Tinkers' Construct", ModRecipeBridge::scanTinkersConstruct);
            if (Loader.isModLoaded("enderio")) passCount += safeModScan("EnderIO", ModRecipeBridge::scanEnderIO);
            if (Loader.isModLoaded("railcraft")) passCount += safeModScan("Railcraft", ModRecipeBridge::scanRailcraft);

            totalCount += passCount;

            String passMsg = tr("[Mod recipes]") + " "
                + tr("[Pass")
                + " "
                + pass
                + "] "
                + tr("Registered")
                + " "
                + passCount
                + " "
                + tr("items")
                + " ("
                + tr("total")
                + ": "
                + totalCount
                + ")";
            ModFileLogger.scanSummary(passMsg);

            if (passCount == 0) {
                String stopMsg = tr("[Mod recipes]") + " "
                    + tr("[Pass")
                    + " "
                    + pass
                    + "] "
                    + tr("No new registrations this pass, stopping");
                ModFileLogger.scanSummary(stopMsg);
                break;
            }

            if (pass < maxPasses) {
                ModFileLogger.scan(
                    tr("[Mod recipes]") + " "
                        + tr("[Pass")
                        + " "
                        + pass
                        + "] "
                        + passCount
                        + " "
                        + tr("new items, retrying for dependencies..."));
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (totalCount > 0) {
            String msg = tr("[Mod recipes]") + " "
                + tr("Registered")
                + " "
                + totalCount
                + " "
                + tr("items from mod-specific recipes in")
                + " "
                + elapsed
                + " ms";
            ModFileLogger.info(msg);
            ModFileLogger.scan(msg);
        }
        statModRecipeRegistered = totalCount;
    }

    // ==================== Utility Helpers / 工具方法 ====================

    /**
     * Tries to load a class by name from multiple candidates. Returns null if none found.
     * ClassNotFoundException is expected (mod not installed), so it is NOT logged.
     *
     * 尝试按名称从多个候选项加载类。如果都找不到则返回 null。
     * ClassNotFoundException 是预期的（模组未安装），因此不记录日志。
     */
    private static Class<?> tryLoadClass(String... classNames) {
        for (String name : classNames) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    /**
     * Tries to extract recipes from a class by name. Returns 0 if class not found.
     * 尝试按类名提取配方。如果类找不到则返回 0。
     */
    private static int tryExtractFromClass(String className, String modLabel) {
        try {
            Class<?> clazz = Class.forName(className);
            logReflect(modLabel, "class loaded: " + className);
            return extractRecipesFromClass(clazz, modLabel);
        } catch (ClassNotFoundException e) {
            logReflect(modLabel, "class not found: " + className);
            return 0;
        }
    }

    /**
     * Logs a reflection event to the scan log file for debugging.
     * All reflection attempts (success and failure) are recorded here, not in console.
     *
     * 将反射事件记录到扫描日志文件以便调试。
     * 所有反射尝试（成功和失败）都记录在这里，不在控制台输出。
     */
    private static void logReflect(String context, String detail) {
        ModFileLogger.scan("[Reflect] " + context + " | " + detail);
    }

    /**
     * Logs a reflection exception to the scan log file.
     * 将反射异常记录到扫描日志文件。
     */
    private static void logReflectError(String context, String operation, Exception e) {
        String msg = e.getClass()
            .getSimpleName();
        if (e.getMessage() != null) msg += ": " + e.getMessage();
        ModFileLogger.scan("[Reflect FAIL] " + context + " | " + operation + " -> " + msg);
    }

    // ==================== AbyssalCraft (深渊国度) ====================

    /**
     * Scans AbyssalCraft's 5 recipe systems via their public singleton API (1.7.10):
     * 1. TransmutatorRecipes.instance().getTransmutationList() -> Map(input->output)
     * 2. CrystallizerRecipes.instance().getCrystallizationList() -> Map(input->ItemStack[]{out1,out2})
     * 3. MaterializerRecipes.instance().getMaterializationList() -> List(Materialization{input[],output})
     * 4. EngraverRecipes.instance().getEngravingList() -> Map(template->coin)
     * 5. Necronomicon Rituals (NecronomiconCreationRitual / NecronomiconInfusionRitual):
     * - Output: getItem() -> ItemStack
     * - Inputs: getOfferings() -> Object[] (ItemStack or String/OreDict)
     * - Central: getSacrifice() -> Object (ItemStack or String/OreDict)
     *
     * The ritual API was added in AbyssalCraft mod version 1.4 (@since 1.4).
     * The 1.7.10 GitHub branch is an early snapshot; actual released JARs include rituals.
     *
     * 扫描深渊国度的 5 种配方系统（通过公开单例 API，1.7.10 版本）：
     * 1. 变质器 — Map(输入->输出)
     * 2. 结晶器 — Map(输入->ItemStack[]{输出1,输出2})
     * 3. 物化器 — List(Materialization{输入数组,输出})
     * 4. 雕刻器 — Map(模板->硬币)
     * 5. 死灵之书仪式（NecronomiconCreationRitual / NecronomiconInfusionRitual）：
     * - 输出: getItem() -> ItemStack
     * - 供品: getOfferings() -> Object[]（ItemStack 或 String/矿辞名）
     * - 祭品: getSacrifice() -> Object（ItemStack 或 String/矿辞名）
     *
     * 仪式 API 从深渊国度 mod 版本 1.4 起加入。
     * GitHub 上的 1.7.10 分支只是早期快照，实际发布的 JAR 包含仪式系统。
     */
    private static int scanAbyssalCraft() {
        Class<?> apiClass = tryLoadClass(
            "com.shinoow.abyssalcraft.api.AbyssalCraftAPI",
            "com.shinoow.abyssalcraft.api.recipe.CrystallizerRecipes");
        if (apiClass == null) return 0;

        int registered = 0;
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " AbyssalCraft " + tr("detected, scanning"));
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== AbyssalCraft ==========");

        registered += scanACTransmutator();
        registered += scanACCrystallizer();
        registered += scanACMaterializer();
        registered += scanACEngraver();
        registered += scanACRituals();

        logModSummary("AbyssalCraft", registered);
        return registered;
    }

    /**
     * TransmutatorRecipes: Map(ItemStack input → ItemStack output)
     * 变质器配方：Map(输入 → 输出)
     */
    private static int scanACTransmutator() {
        try {
            Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.TransmutatorRecipes");
            Method inst = clazz.getMethod("instance");
            Object singleton = inst.invoke(null);
            Method getList = clazz.getMethod("getTransmutationList");
            Object result = getList.invoke(singleton);
            if (result instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) result;
                logReflect(
                    "AbyssalCraft",
                    "TransmutatorRecipes.getTransmutationList() -> Map (size=" + map.size() + ")");
                int reg = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    ItemStack input = toItemStack(entry.getKey());
                    ItemStack output = toItemStack(entry.getValue());
                    reg += tryDeriveFromInput(output, input, "AbyssalCraft-Transmutator");
                }
                logReflect("AbyssalCraft", "Transmutator: derived " + reg + " items");
                return reg;
            }
        } catch (ClassNotFoundException e) {
            logReflect("AbyssalCraft", "TransmutatorRecipes class not found");
        } catch (Exception e) {
            logReflectError("AbyssalCraft", "scanACTransmutator", e);
        }
        return 0;
    }

    /**
     * CrystallizerRecipes: Map(ItemStack input → ItemStack[] {output1, output2})
     * 结晶器配方：Map(输入 → ItemStack[]{输出1, 输出2})
     */
    private static int scanACCrystallizer() {
        try {
            Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.CrystallizerRecipes");
            Method inst = clazz.getMethod("instance");
            Object singleton = inst.invoke(null);
            Method getList = clazz.getMethod("getCrystallizationList");
            Object result = getList.invoke(singleton);
            if (result instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) result;
                logReflect(
                    "AbyssalCraft",
                    "CrystallizerRecipes.getCrystallizationList() -> Map (size=" + map.size() + ")");
                int reg = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    ItemStack input = toItemStack(entry.getKey());
                    if (input == null || input.getItem() == null) continue;
                    Object val = entry.getValue();
                    if (val instanceof ItemStack[]) {
                        for (ItemStack output : (ItemStack[]) val) {
                            if (output != null && output.getItem() != null) {
                                reg += tryDeriveFromInput(output, input, "AbyssalCraft-Crystallizer");
                            }
                        }
                    }
                }
                logReflect("AbyssalCraft", "Crystallizer: derived " + reg + " items");
                return reg;
            }
        } catch (ClassNotFoundException e) {
            logReflect("AbyssalCraft", "CrystallizerRecipes class not found");
        } catch (Exception e) {
            logReflectError("AbyssalCraft", "scanACCrystallizer", e);
        }
        return 0;
    }

    /**
     * MaterializerRecipes: List of Materialization objects with public fields:
     * Materialization.output (ItemStack) and Materialization.input (ItemStack[])
     * 物化器配方：Materialization 对象列表，公开字段 output 和 input
     */
    private static int scanACMaterializer() {
        try {
            Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.MaterializerRecipes");
            Method inst = clazz.getMethod("instance");
            Object singleton = inst.invoke(null);
            Method getList = clazz.getMethod("getMaterializationList");
            Object result = getList.invoke(singleton);
            if (result instanceof List) {
                List<?> list = (List<?>) result;
                logReflect(
                    "AbyssalCraft",
                    "MaterializerRecipes.getMaterializationList() -> List (size=" + list.size() + ")");
                int reg = 0;
                for (Object mat : list) {
                    if (mat == null) continue;
                    try {
                        Field outputField = mat.getClass()
                            .getField("output");
                        Field inputField = mat.getClass()
                            .getField("input");
                        ItemStack output = (ItemStack) outputField.get(mat);
                        ItemStack[] inputs = (ItemStack[]) inputField.get(mat);
                        if (output == null || output.getItem() == null) continue;
                        if (inputs == null || inputs.length == 0) continue;
                        List<ItemStack> inputList = new ArrayList<>();
                        for (ItemStack s : inputs) {
                            if (s != null && s.getItem() != null) inputList.add(s);
                        }
                        if (!inputList.isEmpty()) {
                            reg += tryDeriveFromInputs(output, inputList, "AbyssalCraft-Materializer");
                        }
                    } catch (Exception e) {
                        logReflectError("AbyssalCraft", "Materialization field access", e);
                    }
                }
                logReflect("AbyssalCraft", "Materializer: derived " + reg + " items");
                return reg;
            }
        } catch (ClassNotFoundException e) {
            logReflect("AbyssalCraft", "MaterializerRecipes class not found");
        } catch (Exception e) {
            logReflectError("AbyssalCraft", "scanACMaterializer", e);
        }
        return 0;
    }

    /**
     * EngraverRecipes: Map(ItemStack template → ItemStack coin)
     * 雕刻器配方：Map(模板 → 硬币)
     */
    private static int scanACEngraver() {
        try {
            Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.EngraverRecipes");
            Method inst = clazz.getMethod("instance");
            Object singleton = inst.invoke(null);
            Method getList = clazz.getMethod("getEngravingList");
            Object result = getList.invoke(singleton);
            if (result instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) result;
                logReflect("AbyssalCraft", "EngraverRecipes.getEngravingList() -> Map (size=" + map.size() + ")");
                int reg = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    ItemStack input = toItemStack(entry.getKey());
                    ItemStack output = toItemStack(entry.getValue());
                    reg += tryDeriveFromInput(output, input, "AbyssalCraft-Engraver");
                }
                logReflect("AbyssalCraft", "Engraver: derived " + reg + " items");
                return reg;
            }
        } catch (ClassNotFoundException e) {
            logReflect("AbyssalCraft", "EngraverRecipes class not found");
        } catch (Exception e) {
            logReflectError("AbyssalCraft", "scanACEngraver", e);
        }
        return 0;
    }

    /**
     * Scans AbyssalCraft Necronomicon Rituals via reflection.
     * Ritual classes: NecronomiconCreationRitual, NecronomiconInfusionRitual
     * (both extend NecronomiconRitual, @since mod version 1.4).
     *
     * Strategy:
     * 1. Try to find a ritual registry via AbyssalCraftAPI static fields/methods
     * (e.g. getRituals(), rituals field, internal method handler).
     * 2. If no direct registry, probe the API class's static fields for List/Map
     * containing ritual objects (class name contains "Ritual").
     * 3. For each ritual object, delegate to processRitualObject() which tries:
     * getItem() for output, getOfferings() for Object[] inputs, getSacrifice() for Object input.
     *
     * 通过反射扫描深渊国度死灵之书仪式。
     * 策略：
     * 1. 尝试通过 AbyssalCraftAPI 的静态字段/方法找到仪式注册表
     * 2. 如果没有直接的注册表，探测 API 类的静态字段寻找包含仪式对象的 List/Map
     * 3. 对每个仪式对象调用 processRitualObject()
     */
    private static int scanACRituals() {
        int registered = 0;
        List<Object> ritualObjs = new ArrayList<>();

        try {
            Class<?> apiClass = Class.forName("com.shinoow.abyssalcraft.api.AbyssalCraftAPI");

            // Phase 0: Dump all static methods and fields for diagnostics (skipped when skipDiagnosticDumps)
            // 阶段0：转储所有静态方法和字段用于诊断（配置 skipDiagnosticDumps 时跳过以加快加载）
            if (!ThaumicAllAspect.skipDiagnosticDumps) {
                logReflect("AbyssalCraft", "--- API class dump (methods) ---");
                for (Method m : apiClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) {
                        logReflect(
                            "AbyssalCraft",
                            "  static method: " + m.getName()
                                + "() -> "
                                + m.getReturnType()
                                    .getSimpleName());
                    }
                }
                logReflect("AbyssalCraft", "--- API class dump (fields) ---");
                for (Field f : apiClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        String typeName = f.getType()
                            .getSimpleName();
                        String valInfo = "";
                        try {
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val instanceof List) {
                                List<?> list = (List<?>) val;
                                valInfo = " (List size=" + list.size();
                                if (!list.isEmpty() && list.get(0) != null) {
                                    valInfo += ", element[0]=" + list.get(0)
                                        .getClass()
                                        .getName();
                                }
                                valInfo += ")";
                            } else if (val instanceof Map) {
                                valInfo = " (Map size=" + ((Map<?, ?>) val).size() + ")";
                            } else if (val != null) {
                                valInfo = " = " + val.getClass()
                                    .getName();
                            } else {
                                valInfo = " = null";
                            }
                        } catch (Exception ex) {
                            valInfo = " (access error: " + ex.getMessage() + ")";
                        }
                        logReflect("AbyssalCraft", "  static field: " + f.getName() + " : " + typeName + valInfo);
                    }
                }
                logReflect("AbyssalCraft", "--- end API dump ---");
            }

            // Phase 1: Try direct getter methods for ritual lists
            for (String mName : new String[] { "getRituals", "getCreationRituals", "getInfusionRituals",
                "getNecronomiconRituals", "getRitualList" }) {
                try {
                    Method m = apiClass.getMethod(mName);
                    if (Modifier.isStatic(m.getModifiers())) {
                        Object result = m.invoke(null);
                        if (result instanceof List) {
                            List<?> list = (List<?>) result;
                            logReflect(
                                "AbyssalCraft",
                                "AbyssalCraftAPI." + mName + "() -> List (size=" + list.size() + ")");
                            for (Object obj : list) {
                                if (obj != null) ritualObjs.add(obj);
                            }
                        } else if (result instanceof Map) {
                            Map<?, ?> map = (Map<?, ?>) result;
                            logReflect(
                                "AbyssalCraft",
                                "AbyssalCraftAPI." + mName + "() -> Map (size=" + map.size() + ")");
                            for (Object obj : map.values()) {
                                if (obj != null) ritualObjs.add(obj);
                            }
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    logReflect("AbyssalCraft", "AbyssalCraftAPI." + mName + "() not found");
                } catch (Exception e) {
                    logReflectError("AbyssalCraft", "AbyssalCraftAPI." + mName + "()", e);
                }
            }

            // Phase 2: Scan ALL static List/Map/array fields for ritual-like objects
            if (ritualObjs.isEmpty()) {
                logReflect("AbyssalCraft", "No direct ritual getter found, scanning static fields...");
                for (Field f : apiClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val instanceof List) {
                            List<?> list = (List<?>) val;
                            for (Object obj : list) {
                                if (obj != null && isRitualLike(obj)) {
                                    ritualObjs.add(obj);
                                }
                            }
                            if (!ritualObjs.isEmpty()) {
                                logReflect(
                                    "AbyssalCraft",
                                    "Found " + ritualObjs.size() + " ritual objects in field '" + f.getName() + "'");
                            }
                        } else if (val instanceof Map) {
                            Map<?, ?> map = (Map<?, ?>) val;
                            for (Object obj : map.values()) {
                                if (obj != null && isRitualLike(obj)) {
                                    ritualObjs.add(obj);
                                }
                            }
                            if (!ritualObjs.isEmpty()) {
                                logReflect(
                                    "AbyssalCraft",
                                    "Found " + ritualObjs.size() + " ritual objects in field '" + f.getName() + "'");
                            }
                        } else if (val != null && val.getClass()
                            .isArray()) {
                                Object[] arr = (Object[]) val;
                                for (Object obj : arr) {
                                    if (obj != null && isRitualLike(obj)) {
                                        ritualObjs.add(obj);
                                    }
                                }
                                if (!ritualObjs.isEmpty()) {
                                    logReflect(
                                        "AbyssalCraft",
                                        "Found " + ritualObjs.size()
                                            + " ritual objects in array field '"
                                            + f.getName()
                                            + "'");
                                }
                            }
                    } catch (Exception e) {
                        logReflectError("AbyssalCraft", "field " + f.getName(), e);
                    }
                }
            }

            // Phase 3: Try internal method handler
            if (ritualObjs.isEmpty()) {
                try {
                    Method getHandler = apiClass.getMethod("getInternalMethodHandler");
                    if (Modifier.isStatic(getHandler.getModifiers())) {
                        Object handler = getHandler.invoke(null);
                        if (handler != null) {
                            logReflect(
                                "AbyssalCraft",
                                "Got InternalMethodHandler: " + handler.getClass()
                                    .getName());
                            for (String mName : new String[] { "getRituals", "getAllRituals", "getRitualList" }) {
                                try {
                                    Method m = handler.getClass()
                                        .getMethod(mName);
                                    Object result = m.invoke(handler);
                                    if (result instanceof List) {
                                        List<?> list = (List<?>) result;
                                        for (Object obj : list) {
                                            if (obj != null) ritualObjs.add(obj);
                                        }
                                        logReflect(
                                            "AbyssalCraft",
                                            "handler." + mName + "() -> " + ritualObjs.size() + " rituals");
                                    }
                                } catch (NoSuchMethodException ignored) {} catch (Exception e) {
                                    logReflectError("AbyssalCraft", "handler." + mName + "()", e);
                                }
                            }
                            if (ritualObjs.isEmpty()) {
                                for (Field f : handler.getClass()
                                    .getDeclaredFields()) {
                                    try {
                                        f.setAccessible(true);
                                        Object val = f.get(handler);
                                        if (val instanceof List) {
                                            for (Object obj : (List<?>) val) {
                                                if (obj != null && isRitualLike(obj)) {
                                                    ritualObjs.add(obj);
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                                if (!ritualObjs.isEmpty()) {
                                    logReflect(
                                        "AbyssalCraft",
                                        "Found " + ritualObjs.size() + " ritual objects in handler fields");
                                }
                            }
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    logReflect("AbyssalCraft", "getInternalMethodHandler() not found");
                } catch (Exception e) {
                    logReflectError("AbyssalCraft", "getInternalMethodHandler()", e);
                }
            }

            // Phase 4: RitualRegistry singleton — the confirmed location of ritual data.
            // RitualRegistry has a static 'instance' field (singleton pattern).
            // Rituals are stored inside this instance, not as a static List on the class.
            // RitualRegistry 是单例模式，仪式列表存储在实例的内部字段中，而非类的静态 List。
            if (ritualObjs.isEmpty()) {
                logReflect("AbyssalCraft", "Phase 4: Scanning RitualRegistry singleton...");
                try {
                    Class<?> registryClass = Class.forName("com.shinoow.abyssalcraft.api.ritual.RitualRegistry");
                    Object registry = null;

                    try {
                        Field instField = registryClass.getDeclaredField("instance");
                        instField.setAccessible(true);
                        registry = instField.get(null);
                        logReflect(
                            "AbyssalCraft",
                            "RitualRegistry.instance -> " + (registry != null ? registry.getClass()
                                .getName() : "null"));
                    } catch (NoSuchFieldException e) {
                        logReflect("AbyssalCraft", "RitualRegistry.instance field not found, trying getInstance()");
                        try {
                            Method m = registryClass.getMethod("getInstance");
                            registry = m.invoke(null);
                        } catch (NoSuchMethodException ignored) {}
                    }

                    if (registry != null) {
                        if (!ThaumicAllAspect.skipDiagnosticDumps) {
                            logReflect("AbyssalCraft", "--- RitualRegistry instance methods ---");
                            for (Method m : registry.getClass()
                                .getDeclaredMethods()) {
                                logReflect(
                                    "AbyssalCraft",
                                    "  method: " + m.getName()
                                        + "() -> "
                                        + m.getReturnType()
                                            .getSimpleName());
                            }
                            logReflect("AbyssalCraft", "--- RitualRegistry instance fields ---");
                            for (Field f : registry.getClass()
                                .getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    Object val = Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(registry);
                                    String info = f.getName() + " : "
                                        + f.getType()
                                            .getSimpleName();
                                    if (val instanceof List) {
                                        List<?> list = (List<?>) val;
                                        info += " (List size=" + list.size();
                                        if (!list.isEmpty() && list.get(0) != null) {
                                            info += ", element[0]=" + list.get(0)
                                                .getClass()
                                                .getName();
                                        }
                                        info += ")";
                                    } else if (val instanceof Map) {
                                        info += " (Map size=" + ((Map<?, ?>) val).size() + ")";
                                    } else if (val != null) {
                                        info += " = " + val.getClass()
                                            .getName();
                                    }
                                    logReflect("AbyssalCraft", "  field: " + info);
                                } catch (Exception ex) {
                                    logReflect(
                                        "AbyssalCraft",
                                        "  field: " + f.getName() + " (error: " + ex.getMessage() + ")");
                                }
                            }
                            logReflect("AbyssalCraft", "--- end RitualRegistry dump ---");
                        }

                        // Try getter methods on the singleton
                        for (String mName : new String[] { "getRituals", "getRecipes", "getRitualList", "getAllRituals",
                            "getCreationRituals", "getInfusionRituals", "getRegisteredRituals" }) {
                            try {
                                Method m = registry.getClass()
                                    .getMethod(mName);
                                Object result = m.invoke(registry);
                                if (result instanceof List) {
                                    List<?> list = (List<?>) result;
                                    logReflect(
                                        "AbyssalCraft",
                                        "RitualRegistry." + mName + "() -> List (size=" + list.size() + ")");
                                    for (Object obj : list) {
                                        if (obj != null) ritualObjs.add(obj);
                                    }
                                    if (!ritualObjs.isEmpty()) break;
                                } else if (result instanceof Map) {
                                    Map<?, ?> map = (Map<?, ?>) result;
                                    logReflect(
                                        "AbyssalCraft",
                                        "RitualRegistry." + mName + "() -> Map (size=" + map.size() + ")");
                                    for (Object obj : map.values()) {
                                        if (obj != null) ritualObjs.add(obj);
                                    }
                                    if (!ritualObjs.isEmpty()) break;
                                }
                            } catch (NoSuchMethodException ignored) {} catch (Exception e) {
                                logReflectError("AbyssalCraft", "RitualRegistry." + mName + "()", e);
                            }
                        }

                        // Scan instance fields for List/Map containing ritual-like objects
                        if (ritualObjs.isEmpty()) {
                            logReflect("AbyssalCraft", "No getter found, scanning instance fields...");
                            for (Field f : registry.getClass()
                                .getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    Object val = Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(registry);
                                    if (val instanceof List) {
                                        List<?> list = (List<?>) val;
                                        if (!list.isEmpty()) {
                                            Object first = list.get(0);
                                            if (first != null && isRitualLike(first)) {
                                                for (Object obj : list) {
                                                    if (obj != null) ritualObjs.add(obj);
                                                }
                                                logReflect(
                                                    "AbyssalCraft",
                                                    "Found " + ritualObjs.size()
                                                        + " rituals in field '"
                                                        + f.getName()
                                                        + "'");
                                                break;
                                            }
                                        }
                                    } else if (val instanceof Map) {
                                        Map<?, ?> map = (Map<?, ?>) val;
                                        for (Object obj : map.values()) {
                                            if (obj != null && isRitualLike(obj)) {
                                                ritualObjs.add(obj);
                                            }
                                        }
                                        if (!ritualObjs.isEmpty()) {
                                            logReflect(
                                                "AbyssalCraft",
                                                "Found " + ritualObjs.size()
                                                    + " rituals in map field '"
                                                    + f.getName()
                                                    + "'");
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }

                        // Last resort: collect ALL objects from any non-empty List field
                        if (ritualObjs.isEmpty()) {
                            logReflect("AbyssalCraft", "No ritual-like fields, scanning ALL List fields...");
                            for (Field f : registry.getClass()
                                .getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    Object val = Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(registry);
                                    if (val instanceof List) {
                                        List<?> list = (List<?>) val;
                                        if (!list.isEmpty()) {
                                            for (Object obj : list) {
                                                if (obj != null) ritualObjs.add(obj);
                                            }
                                            logReflect(
                                                "AbyssalCraft",
                                                "Collected " + ritualObjs.size()
                                                    + " objects from field '"
                                                    + f.getName()
                                                    + "' (element type: "
                                                    + list.get(0)
                                                        .getClass()
                                                        .getName()
                                                    + ")");
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    } else {
                        logReflect("AbyssalCraft", "RitualRegistry singleton is null");
                    }
                } catch (ClassNotFoundException e) {
                    logReflect("AbyssalCraft", "RitualRegistry class not found");
                } catch (Exception e) {
                    logReflectError("AbyssalCraft", "Phase 4: RitualRegistry", e);
                }
            }
        } catch (ClassNotFoundException e) {
            logReflect("AbyssalCraft", "AbyssalCraftAPI class not found for ritual scanning");
            return 0;
        } catch (Exception e) {
            logReflectError("AbyssalCraft", "scanACRituals", e);
        }

        // Process all collected ritual objects
        if (ritualObjs.isEmpty()) {
            logReflect("AbyssalCraft", "No Necronomicon ritual objects found");
        } else {
            logReflect("AbyssalCraft", "Processing " + ritualObjs.size() + " ritual objects...");
            for (Object ritual : ritualObjs) {
                try {
                    registered += processRitualObject(ritual, "AbyssalCraft-Ritual");
                } catch (Exception e) {
                    logReflectError(
                        "AbyssalCraft",
                        "processRitualObject for " + ritual.getClass()
                            .getName(),
                        e);
                }
            }
            logReflect("AbyssalCraft", "Rituals: derived " + registered + " items");
        }

        return registered;
    }

    /**
     * Checks if an object looks like a ritual (class name contains "ritual", "rite", "necro",
     * or it has ritual-like methods such as getOfferings/getSacrifice/getItem).
     *
     * 检查对象是否看起来像仪式（类名包含 ritual/rite/necro，
     * 或具有 getOfferings/getSacrifice/getItem 等仪式方法）。
     */
    private static boolean isRitualLike(Object obj) {
        String cn = obj.getClass()
            .getName()
            .toLowerCase();
        if (cn.contains("ritual") || cn.contains("rite") || cn.contains("necro")) return true;
        return hasRitualMethods(obj);
    }

    // ==================== Witchery (巫术) ====================

    /**
     * Scans Witchery's recipe systems: Kettle (大釜), Distillery (蒸馏器),
     * Spinning Wheel (纺车), Oven (巫术烤炉), and Rite rituals (仪式).
     *
     * Witchery stores recipes in singleton managers or static registries.
     * Kettle/Distillery recipes typically have getOutput()/getInputs() methods.
     * Rites are ritual-like objects with offerings + output.
     *
     * 扫描巫术的配方系统：大釜、蒸馏器、纺车、巫术烤炉和仪式。
     * 巫术将配方存储在单例管理器或静态注册表中。
     */
    private static int scanWitchery() {
        Class<?> detected = tryLoadClass("com.emoniph.witchery.Witchery", "com.emoniph.witchery.WitcheryAPI");
        if (detected == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Witchery ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Witchery " + tr("detected, scanning"));

        // Kettle recipes (大釜 — the main Witchery crafting station)
        // 大釜配方（巫术的主要合成站）
        String[] kettleClasses = { "com.emoniph.witchery.crafting.KettleRecipes",
            "com.emoniph.witchery.brewing.KettleRecipes", "com.emoniph.witchery.api.KettleRecipes" };
        for (String c : kettleClasses) registered += tryExtractFromClass(c, "Witchery-Kettle");

        // Distillery (蒸馏器)
        String[] distilleryClasses = { "com.emoniph.witchery.crafting.DistilleryRecipes",
            "com.emoniph.witchery.api.DistilleryRecipes" };
        for (String c : distilleryClasses) registered += tryExtractFromClass(c, "Witchery-Distillery");

        // Spinning Wheel (纺车)
        String[] spinningClasses = { "com.emoniph.witchery.crafting.SpinningRecipes",
            "com.emoniph.witchery.api.SpinningRecipes" };
        for (String c : spinningClasses) registered += tryExtractFromClass(c, "Witchery-Spinning");

        // Oven (巫术烤炉)
        String[] ovenClasses = { "com.emoniph.witchery.crafting.WitchesOvenRecipes",
            "com.emoniph.witchery.crafting.OvenRecipes" };
        for (String c : ovenClasses) registered += tryExtractFromClass(c, "Witchery-Oven");

        // Rite / Ritual system (仪式系统)
        // Witchery's rites are stored in a registry; each rite may have offerings + output
        // 巫术的仪式存储在注册表中；每个仪式可能有祭品和产物
        String[] riteClasses = { "com.emoniph.witchery.ritual.RiteRegistry", "com.emoniph.witchery.ritual.Rites",
            "com.emoniph.witchery.api.RiteRegistry" };
        for (String className : riteClasses) {
            try {
                Class<?> riteClass = Class.forName(className);
                logReflect("Witchery-Rite", "loaded rite class: " + className);
                List<Object> ritualObjs = new ArrayList<>();
                collectRitualObjectsFromClass(riteClass, ritualObjs, "Witchery-Rite");
                for (Object rite : ritualObjs) {
                    if (rite == null) continue;
                    try {
                        registered += processRitualObject(rite, "Witchery-Rite");
                    } catch (Exception e) {
                        logReflectError("Witchery-Rite", "processRitualObject", e);
                    }
                }
                registered += extractRecipesFromClass(riteClass, "Witchery-Rite");
            } catch (ClassNotFoundException e) {
                logReflect("Witchery-Rite", "class not found: " + className);
            }
        }

        // Infusion (灌注)
        String[] infusionClasses = { "com.emoniph.witchery.infusion.InfusionRecipes",
            "com.emoniph.witchery.crafting.InfusionRecipes" };
        for (String c : infusionClasses) registered += tryExtractFromClass(c, "Witchery-Infusion");

        logModSummary("Witchery", registered);
        return registered;
    }

    // ==================== Blood Magic (血魔法) ====================

    /**
     * Scans Blood Magic's recipe systems: Blood Altar (血祭坛), Alchemy Table (炼金术台),
     * and Binding rituals (绑定仪式).
     *
     * Blood Magic uses static registries in its API package. Altar recipes map
     * input→output with tier/LP requirements. Alchemy recipes have input arrays.
     *
     * 扫描血魔法的配方系统：血祭坛、炼金术台和绑定仪式。
     * 血魔法在其 API 包中使用静态注册表。
     */
    private static int scanBloodMagic() {
        Class<?> detected = tryLoadClass(
            "WayofTime.alchemicalWizardry.api.BloodMagicAPI",
            "WayofTime.alchemicalWizardry.BloodMagicAPI",
            "WayofTime.alchemicalWizardry.ModBloodMagic",
            "WayofTime.alchemicalWizardry.AlchemicalWizardry");
        if (detected == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Blood Magic ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Blood Magic " + tr("detected, scanning"));

        // Blood Altar recipes (血祭坛配方)
        String[] altarClasses = { "WayofTime.alchemicalWizardry.api.altarRecipe.AltarRecipeRegistry",
            "WayofTime.alchemicalWizardry.api.altar.AltarRecipeRegistry",
            "WayofTime.alchemicalWizardry.common.AltarRecipeRegistry" };
        for (String c : altarClasses) registered += tryExtractFromClass(c, "BloodMagic-Altar");

        // Alchemy recipes (炼金术配方)
        String[] alchemyClasses = { "WayofTime.alchemicalWizardry.api.alchemy.AlchemyRecipeRegistry",
            "WayofTime.alchemicalWizardry.api.AlchemyRecipeRegistry" };
        for (String c : alchemyClasses) registered += tryExtractFromClass(c, "BloodMagic-Alchemy");

        // Binding recipes (绑定配方)
        String[] bindingClasses = { "WayofTime.alchemicalWizardry.api.bindingRecipe.BindingRecipeRegistry",
            "WayofTime.alchemicalWizardry.api.binding.BindingRecipeRegistry" };
        for (String c : bindingClasses) registered += tryExtractFromClass(c, "BloodMagic-Binding");

        // Also try the main API class fields/methods
        // 也尝试主 API 类的字段/方法
        registered += extractRecipesFromClass(detected, "BloodMagic");

        // Ritual-like objects (仪式类对象)
        String[] ritualClasses = { "WayofTime.alchemicalWizardry.api.ritual.RitualRegistry",
            "WayofTime.alchemicalWizardry.api.rituals.RitualRegistry" };
        for (String className : ritualClasses) {
            try {
                Class<?> ritualClass = Class.forName(className);
                logReflect("BloodMagic-Ritual", "loaded: " + className);
                List<Object> ritualObjs = new ArrayList<>();
                collectRitualObjectsFromClass(ritualClass, ritualObjs, "BloodMagic-Ritual");
                for (Object r : ritualObjs) {
                    if (r == null) continue;
                    try {
                        registered += processRitualObject(r, "BloodMagic-Ritual");
                    } catch (Exception e) {
                        logReflectError("BloodMagic-Ritual", "processRitualObject", e);
                    }
                }
                registered += extractRecipesFromClass(ritualClass, "BloodMagic-Ritual");
            } catch (ClassNotFoundException e) {
                logReflect("BloodMagic-Ritual", "class not found: " + className);
            }
        }

        logModSummary("Blood Magic", registered);
        return registered;
    }

    // ==================== Botania (植物魔法) ====================

    /**
     * Scans Botania's recipe systems: Mana Infusion (魔力注入), Runic Altar (符文祭坛),
     * Petal Apothecary (花瓣炼药台), Elven Trade (精灵贸易).
     *
     * Botania stores recipes as static Lists in BotaniaAPI:
     * - manaInfusionRecipes, petalRecipes, runeAltarRecipes, elvenTradeRecipes
     * Each recipe object has getOutput() and getInputs() methods.
     *
     * 扫描植物魔法的配方系统：魔力注入、符文祭坛、花瓣炼药台、精灵贸易。
     * 植物魔法在 BotaniaAPI 中存储为静态 List。
     */
    private static int scanBotania() {
        Class<?> apiClass = tryLoadClass(
            "vazkii.botania.api.BotaniaAPI",
            "vazkii.botania.api.recipe.RecipeManaInfusion");
        if (apiClass == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Botania ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Botania " + tr("detected, scanning"));

        // BotaniaAPI has static List fields for each recipe type
        // BotaniaAPI 有每种配方类型的静态 List 字段
        registered += extractRecipesFromClass(apiClass, "Botania");

        // Also try specific recipe classes that may have their own static lists
        // 也尝试可能有自己静态列表的特定配方类
        String[] recipeClasses = { "vazkii.botania.api.recipe.RecipeManaInfusion",
            "vazkii.botania.api.recipe.RecipeRuneAltar", "vazkii.botania.api.recipe.RecipePetals",
            "vazkii.botania.api.recipe.RecipeElvenTrade", "vazkii.botania.api.recipe.RecipePureDaisy",
            "vazkii.botania.api.recipe.RecipeBrew" };
        for (String c : recipeClasses) registered += tryExtractFromClass(c, "Botania");

        logModSummary("Botania", registered);
        return registered;
    }

    // ==================== Forestry (林业) ====================

    /**
     * Scans Forestry's recipe systems: Carpenter (木工机), Centrifuge (离心机),
     * Squeezer (榨汁机), Fermenter (发酵机), Still (蒸馏器), Moistener (湿润器).
     *
     * Forestry uses RecipeManagers with static manager fields.
     * Each manager has getRecipes() returning a collection.
     *
     * 扫描林业的配方系统：木工机、离心机、榨汁机、发酵机、蒸馏器、湿润器。
     * 林业使用 RecipeManagers 及其静态管理器字段。
     */
    private static int scanForestry() {
        Class<?> detected = tryLoadClass(
            "forestry.api.recipes.RecipeManagers",
            "forestry.api.recipes.ICarpenterManager",
            "forestry.Forestry");
        if (detected == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Forestry ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Forestry " + tr("detected, scanning"));

        // RecipeManagers has static fields for each machine type
        // RecipeManagers 有每种机器类型的静态字段
        registered += extractRecipesFromClass(detected, "Forestry");

        String[] managerClasses = { "forestry.api.recipes.RecipeManagers",
            "forestry.factory.recipes.CarpenterRecipeManager", "forestry.factory.recipes.CentrifugeRecipeManager",
            "forestry.factory.recipes.FabricatorRecipeManager", "forestry.factory.recipes.FermenterRecipeManager",
            "forestry.factory.recipes.MoistenerRecipeManager", "forestry.factory.recipes.SqueezerRecipeManager",
            "forestry.factory.recipes.StillRecipeManager" };
        for (String c : managerClasses) registered += tryExtractFromClass(c, "Forestry");

        logModSummary("Forestry", registered);
        return registered;
    }

    // ==================== Tinkers' Construct (匠魂) ====================

    /**
     * Scans Tinkers' Construct's recipe systems: Smeltery (冶炼炉),
     * Casting Table/Basin (浇铸台/盆).
     *
     * TConstruct stores recipes in TConstructRegistry and specific recipe classes.
     *
     * 扫描匠魂的配方系统：冶炼炉、浇铸台/盆。
     * 匠魂在 TConstructRegistry 和特定配方类中存储配方。
     */
    private static int scanTinkersConstruct() {
        Class<?> detected = tryLoadClass("tconstruct.library.TConstructRegistry", "tconstruct.TConstruct");
        if (detected == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Tinkers' Construct ==========");
        ModFileLogger
            .info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Tinkers' Construct " + tr("detected, scanning"));

        registered += extractRecipesFromClass(detected, "TConstruct");

        String[] recipeClasses = { "tconstruct.library.TConstructRegistry", "tconstruct.library.crafting.CastingRecipe",
            "tconstruct.library.crafting.AlloyMix", "tconstruct.library.crafting.DryingRackRecipes",
            "tconstruct.library.crafting.LiquidCasting", "tconstruct.smeltery.TinkersSmeltery" };
        for (String c : recipeClasses) registered += tryExtractFromClass(c, "TConstruct");

        logModSummary("Tinkers' Construct", registered);
        return registered;
    }

    // ==================== EnderIO (末影接口) ====================

    /**
     * Scans EnderIO's recipe systems: Alloy Smelter (合金冶炼炉),
     * SAG Mill (SAG磨粉机), Enchanter (附魔器).
     *
     * EnderIO uses manager classes in crazypants.enderio.machine.* packages.
     *
     * 扫描 EnderIO 的配方系统：合金冶炼炉、SAG磨粉机、附魔器。
     * EnderIO 在 crazypants.enderio.machine.* 包中使用管理器类。
     */
    private static int scanEnderIO() {
        Class<?> detected = tryLoadClass("crazypants.enderio.EnderIO", "crazypants.enderio.api.EnderIOAPI");
        if (detected == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== EnderIO ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " EnderIO " + tr("detected, scanning"));

        String[] recipeClasses = { "crazypants.enderio.machine.alloy.AlloyRecipeManager",
            "crazypants.enderio.machine.alloy.BasicAlloyRecipe",
            "crazypants.enderio.machine.sagmill.SagMillRecipeManager",
            "crazypants.enderio.machine.enchanter.EnchanterRecipeManager",
            "crazypants.enderio.machine.crusher.CrusherRecipeManager",
            "crazypants.enderio.machine.recipe.RecipeConfig" };
        for (String c : recipeClasses) registered += tryExtractFromClass(c, "EnderIO");

        logModSummary("EnderIO", registered);
        return registered;
    }

    // ==================== Railcraft (铁路) ====================

    /**
     * Scans Railcraft's recipe systems: Rolling Machine (轧制机),
     * Blast Furnace (高炉), Rock Crusher (碎石机), Coke Oven (焦炉).
     *
     * Railcraft uses RailcraftCraftingManager and specific handler classes.
     *
     * 扫描 Railcraft 的配方系统：轧制机、高炉、碎石机、焦炉。
     * Railcraft 使用 RailcraftCraftingManager 和特定处理器类。
     */
    private static int scanRailcraft() {
        Class<?> detected = tryLoadClass(
            "mods.railcraft.api.crafting.RailcraftCraftingManager",
            "mods.railcraft.common.Railcraft");
        if (detected == null) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Railcraft ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Railcraft " + tr("detected, scanning"));

        registered += extractRecipesFromClass(detected, "Railcraft");

        String[] recipeClasses = { "mods.railcraft.api.crafting.RailcraftCraftingManager",
            "mods.railcraft.api.crafting.IBlastFurnaceCraftingManager",
            "mods.railcraft.api.crafting.ICokeOvenCraftingManager",
            "mods.railcraft.api.crafting.IRockCrusherCraftingManager",
            "mods.railcraft.common.util.crafting.RollingMachineCraftingManager",
            "mods.railcraft.common.util.crafting.BlastFurnaceCraftingManager",
            "mods.railcraft.common.util.crafting.CokeOvenCraftingManager",
            "mods.railcraft.common.util.crafting.RockCrusherCraftingManager" };
        for (String c : recipeClasses) registered += tryExtractFromClass(c, "Railcraft");

        logModSummary("Railcraft", registered);
        return registered;
    }

    // ==================== Mod Summary Helper ====================

    /**
     * Logs a per-mod summary line if any items were registered.
     * 如果有物品被注册，记录每个模组的总结行。
     */
    private static void logModSummary(String modName, int registered) {
        if (registered > 0) {
            ModFileLogger.scan(
                tr("[Mod recipes]") + " " + modName + ": " + tr("registered") + " " + registered + " " + tr("items"));
        } else {
            ModFileLogger.scan(tr("[Mod recipes]") + " " + modName + ": " + tr("no new items registered"));
        }
    }

    // ==================== Ritual Object Handling / 仪式对象处理 ====================

    /**
     * Collects ritual objects from static fields of a class.
     * A List field is treated as a ritual list if its elements' class name contains
     * "ritual" or "necro" or "rite", or if the elements have ritual-typical methods.
     *
     * 从类的静态字段中收集仪式对象。
     * 如果 List 字段的元素类名包含 "ritual"/"necro"/"rite"，或者元素具有仪式典型方法，
     * 则将其视为仪式列表。
     */
    private static void collectRitualObjectsFromClass(Class<?> clazz, List<Object> collector, String context) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof List) {
                        List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            Object first = null;
                            for (Object o : list) {
                                if (o != null) {
                                    first = o;
                                    break;
                                }
                            }
                            if (first != null) {
                                String typeName = first.getClass()
                                    .getName()
                                    .toLowerCase();
                                if (typeName.contains("ritual") || typeName.contains("necro")
                                    || typeName.contains("rite")) {
                                    logReflect(
                                        context,
                                        "found ritual list in field " + f.getName()
                                            + " (type="
                                            + first.getClass()
                                                .getName()
                                            + ", size="
                                            + list.size()
                                            + ")");
                                    collector.addAll(list);
                                } else if (hasRitualMethods(first)) {
                                    logReflect(
                                        context,
                                        "found ritual-like list in field " + f.getName()
                                            + " (type="
                                            + first.getClass()
                                                .getName()
                                            + ", size="
                                            + list.size()
                                            + ") via method detection");
                                    collector.addAll(list);
                                }
                            }
                        }
                    } else if (val instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) val;
                        for (Object v : map.values()) {
                            if (v != null) {
                                String typeName = v.getClass()
                                    .getName()
                                    .toLowerCase();
                                if (typeName.contains("ritual") || typeName.contains("necro")
                                    || typeName.contains("rite")) {
                                    logReflect(
                                        context,
                                        "found ritual map in field " + f.getName() + " (size=" + map.size() + ")");
                                    collector.addAll(map.values());
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logReflectError(context, "field " + f.getName() + " in " + clazz.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
    }

    /**
     * Checks if an object has methods typical of a ritual recipe.
     * Returns true if at least 2 of: getOfferings, getSacrifice, getOutput, getRecipeOutput exist.
     *
     * 检查对象是否具有仪式配方的典型方法。
     * 如果 getOfferings, getSacrifice, getOutput, getRecipeOutput 中至少 2 个存在则返回 true。
     */
    private static boolean hasRitualMethods(Object obj) {
        int found = 0;
        for (String name : new String[] { "getOfferings", "getSacrifice", "getOutput", "getRecipeOutput" }) {
            try {
                obj.getClass()
                    .getMethod(name);
                found++;
            } catch (NoSuchMethodException ignored) {}
        }
        return found >= 2;
    }

    /**
     * Processes a single ritual object: extracts output + inputs (offerings + sacrifice).
     *
     * Uses reflection to try multiple method names for output, offerings, and sacrifice.
     * All attempts (success and failure) are logged to the scan log.
     *
     * 处理单个仪式对象：提取产物 + 输入材料（祭品 + 祭坛祭品）。
     * 使用反射尝试多个方法名。所有尝试（成功和失败）都记录到扫描日志。
     */
    private static int processRitualObject(Object ritual, String modLabel) {
        String ritualType = ritual.getClass()
            .getName();
        ItemStack output = null;
        List<ItemStack> allInputs = new ArrayList<>();

        // --- Extract output ---
        // NecronomiconCreationRitual uses getItem(), not getOutput()
        // NecronomiconCreationRitual 使用 getItem() 而非 getOutput()
        for (String mName : new String[] { "getItem", "getOutput", "getRecipeOutput", "getResult" }) {
            try {
                Method m = ritual.getClass()
                    .getMethod(mName);
                Object result = m.invoke(ritual);
                if (result instanceof ItemStack) {
                    output = (ItemStack) result;
                    logReflect(modLabel, ritualType + "." + mName + "() -> ItemStack OK");
                    break;
                }
            } catch (NoSuchMethodException e) {
                logReflect(modLabel, ritualType + "." + mName + "() not found");
            } catch (Exception e) {
                logReflectError(modLabel, ritualType + "." + mName + "()", e);
            }
        }

        if (output == null) {
            output = findItemStackField(ritual, modLabel, "item", "output", "result");
        }
        if (output == null || output.getItem() == null) {
            logReflect(modLabel, ritualType + ": no output found, skipping");
            return 0;
        }
        // Always extract inputs and derive: if item has no aspects we register; if it has aspects
        // we merge/improve (tryDeriveFromInputs handles both). Do not skip based on hasAspect here.
        // 始终提取输入并推导：无要素则注册，有要素则合并/增强（由 tryDeriveFromInputs 统一处理）。此处不因已有要素而跳过。

        // --- Extract offerings (pedestal items) ---
        // NecronomiconRitual.getOfferings() returns Object[] — elements can be
        // ItemStack, String (OreDict name), or other Objects.
        // NecronomiconRitual.getOfferings() 返回 Object[] — 元素可能是
        // ItemStack、String（矿辞名）或其他对象。
        for (String mName : new String[] { "getOfferings", "getInputs", "getIngredients", "getComponents" }) {
            try {
                Method m = ritual.getClass()
                    .getMethod(mName);
                Object result = m.invoke(ritual);
                if (result instanceof Object[]) {
                    for (Object o : (Object[]) result) {
                        if (o == null) continue;
                        ItemStack s = objectToItemStack(o);
                        if (s != null && s.getItem() != null) allInputs.add(s);
                    }
                    logReflect(modLabel, ritualType + "." + mName + "() -> " + allInputs.size() + " offerings");
                    break;
                } else if (result instanceof List) {
                    for (Object o : (List<?>) result) {
                        ItemStack s = objectToItemStack(o);
                        if (s != null && s.getItem() != null) allInputs.add(s);
                    }
                    logReflect(modLabel, ritualType + "." + mName + "() -> " + allInputs.size() + " offerings");
                    break;
                }
            } catch (NoSuchMethodException e) {
                logReflect(modLabel, ritualType + "." + mName + "() not found");
            } catch (Exception e) {
                logReflectError(modLabel, ritualType + "." + mName + "()", e);
            }
        }

        if (allInputs.isEmpty()) {
            collectItemStackArrayField(ritual, allInputs, modLabel, "offerings", "inputs", "ingredients");
        }

        // --- Extract sacrifice / central item ---
        // getSacrifice() returns Object (can be ItemStack, String/OreDict, or null)
        // getSacrifice() 返回 Object（可能是 ItemStack、String/矿辞名或 null）
        for (String mName : new String[] { "getSacrifice", "getInput", "getCatalyst", "getCentralItem" }) {
            try {
                Method m = ritual.getClass()
                    .getMethod(mName);
                Object result = m.invoke(ritual);
                if (result != null) {
                    ItemStack sacrifice = objectToItemStack(result);
                    if (sacrifice != null && sacrifice.getItem() != null) {
                        allInputs.add(sacrifice);
                        logReflect(modLabel, ritualType + "." + mName + "() -> sacrifice OK");
                    }
                    break;
                }
            } catch (NoSuchMethodException e) {
                logReflect(modLabel, ritualType + "." + mName + "() not found");
            } catch (Exception e) {
                logReflectError(modLabel, ritualType + "." + mName + "()", e);
            }
        }

        if (allInputs.isEmpty()) {
            ItemStack sacrifice = findItemStackField(ritual, modLabel, "sacrifice", "input", "catalyst", "centralItem");
            if (sacrifice != null && sacrifice.getItem() != null) allInputs.add(sacrifice);
        }

        if (allInputs.isEmpty()) {
            logReflect(modLabel, ritualType + ": no inputs found, skipping");
            return 0;
        }

        return tryDeriveFromInputs(output, allInputs, modLabel);
    }

    // ==================== Field Reflection Helpers / 字段反射辅助 ====================

    /**
     * Finds the first non-null ItemStack field from an object whose name matches one of the hints.
     * 从对象中查找名称匹配提示的第一个非空 ItemStack 字段。
     */
    private static ItemStack findItemStackField(Object obj, String context, String... nameHints) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    String fn = f.getName()
                        .toLowerCase();
                    for (String hint : nameHints) {
                        if (fn.contains(hint.toLowerCase())) {
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            if (val instanceof ItemStack) {
                                logReflect(context, "found ItemStack field: " + f.getName());
                                return (ItemStack) val;
                            }
                        }
                    }
                } catch (Exception e) {
                    logReflectError(context, "field " + f.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Collects ItemStack items from array or List fields whose names match the hints.
     * 从名称匹配提示的数组或 List 字段中收集 ItemStack 物品。
     */
    private static void collectItemStackArrayField(Object obj, List<ItemStack> collector, String context,
        String... nameHints) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    String fn = f.getName()
                        .toLowerCase();
                    for (String hint : nameHints) {
                        if (fn.contains(hint.toLowerCase())) {
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            if (val instanceof ItemStack[]) {
                                for (ItemStack s : (ItemStack[]) val) {
                                    if (s != null && s.getItem() != null) collector.add(s);
                                }
                                logReflect(
                                    context,
                                    "found ItemStack[] field: " + f.getName() + " (" + collector.size() + " items)");
                                return;
                            } else if (val instanceof List) {
                                for (Object o : (List<?>) val) {
                                    ItemStack s = toItemStack(o);
                                    if (s != null && s.getItem() != null) collector.add(s);
                                }
                                logReflect(
                                    context,
                                    "found List field: " + f.getName() + " (" + collector.size() + " items)");
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    logReflectError(context, "field " + f.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
    }

    // ==================== Generic Recipe Extraction / 通用配方提取 ====================

    /**
     * Generic reflective recipe extraction from a class.
     * Tries getInstance()/getRecipes() methods, then scans static and instance fields.
     * All reflection attempts are logged to the scan log.
     *
     * 从一个类中通用地反射提取配方。
     * 先尝试 getInstance()/getRecipes() 方法，再扫描静态和实例字段。
     * 所有反射尝试都记录到扫描日志。
     */
    private static int extractRecipesFromClass(Class<?> clazz, String modLabel) {
        int registered = 0;
        Object instance = null;

        // Phase 1: Try to obtain a singleton instance via getInstance()/instance()
        // 阶段 1：尝试通过 getInstance()/instance() 获取单例实例
        for (String mName : new String[] { "getInstance", "instance" }) {
            try {
                Method m = clazz.getMethod(mName);
                if (Modifier.isStatic(m.getModifiers())) {
                    Object result = m.invoke(null);
                    if (result != null) {
                        logReflect(
                            modLabel,
                            clazz.getSimpleName() + "."
                                + mName
                                + "() -> "
                                + result.getClass()
                                    .getName());
                        instance = result;
                        break;
                    }
                }
            } catch (NoSuchMethodException ignored) {} catch (Exception e) {
                logReflectError(modLabel, clazz.getSimpleName() + "." + mName + "()", e);
            }
        }

        // Phase 2: Try recipe-returning methods (static or on the instance)
        // 阶段 2：尝试返回配方的方法（静态方法或实例方法）
        for (String mName : new String[] { "getRecipes", "getRecipeList", "getTransmutations", "getCrystallizations",
            "getSmeltingList", "getCraftings", "getAllRecipes" }) {
            try {
                Method m = clazz.getMethod(mName);
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                Object target = isStatic ? null : instance;
                if (!isStatic && target == null) continue;
                Object result = m.invoke(target);
                if (result instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) result;
                    logReflect(modLabel, clazz.getSimpleName() + "." + mName + "() -> Map (size=" + map.size() + ")");
                    registered += processRecipeMap(map, modLabel);
                } else if (result instanceof List) {
                    List<?> list = (List<?>) result;
                    logReflect(modLabel, clazz.getSimpleName() + "." + mName + "() -> List (size=" + list.size() + ")");
                    registered += processRecipeList(list, modLabel);
                }
            } catch (NoSuchMethodException ignored) {} catch (Exception e) {
                logReflectError(modLabel, clazz.getSimpleName() + "." + mName + "()", e);
            }
        }

        // Phase 3: Scan instance fields (if we have an instance)
        // 阶段 3：扫描实例字段（如果有实例的话）
        if (instance != null) {
            registered += scanFieldsForRecipes(instance, instance.getClass(), modLabel);
        }

        // Phase 4: Scan static fields only
        // 阶段 4：仅扫描静态字段
        registered += scanStaticFields(clazz, modLabel);

        return registered;
    }

    /**
     * Scans instance fields of a class for Map/List recipe registries.
     * Only reads non-static fields using the given instance.
     *
     * 扫描类的实例字段，查找 Map/List 配方注册表。
     * 仅使用给定实例读取非静态字段。
     */
    private static int scanFieldsForRecipes(Object instance, Class<?> clazz, String modLabel) {
        if (instance == null) return 0;
        int registered = 0;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(instance);
                    if (val instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) val;
                        if (!map.isEmpty()) {
                            logReflect(
                                modLabel,
                                "field " + c.getSimpleName() + "." + f.getName() + " -> Map (size=" + map.size() + ")");
                            registered += processRecipeMap(map, modLabel);
                        }
                    } else if (val instanceof List) {
                        List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            logReflect(
                                modLabel,
                                "field " + c
                                    .getSimpleName() + "." + f.getName() + " -> List (size=" + list.size() + ")");
                            registered += processRecipeList(list, modLabel);
                        }
                    }
                } catch (Exception e) {
                    logReflectError(
                        modLabel,
                        "field " + c.getSimpleName() + "." + f.getName() + " (instance=" + (instance != null) + ")",
                        e);
                }
            }
            c = c.getSuperclass();
        }
        return registered;
    }

    /**
     * Scans only static fields of a class for Map/List recipe registries.
     * Safe to call without an instance — f.get(null) is valid for static fields.
     *
     * 仅扫描类的静态字段，查找 Map/List 配方注册表。
     * 无需实例即可安全调用 — 对静态字段 f.get(null) 是合法的。
     */
    private static int scanStaticFields(Class<?> clazz, String modLabel) {
        int registered = 0;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    if (val instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) val;
                        if (!map.isEmpty()) {
                            logReflect(
                                modLabel,
                                "static field " + c
                                    .getSimpleName() + "." + f.getName() + " -> Map (size=" + map.size() + ")");
                            registered += processRecipeMap(map, modLabel);
                        }
                    } else if (val instanceof List) {
                        List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            logReflect(
                                modLabel,
                                "static field " + c
                                    .getSimpleName() + "." + f.getName() + " -> List (size=" + list.size() + ")");
                            registered += processRecipeList(list, modLabel);
                        }
                    } else if (isManagerCandidate(val)) {
                        String path = c.getSimpleName() + "." + f.getName();
                        registered += tryExtractFromManager(val, modLabel, path);
                    }
                } catch (Exception e) {
                    logReflectError(modLabel, "static field " + c.getSimpleName() + "." + f.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
        return registered;
    }

    /**
     * Checks whether an object looks like a recipe manager worth probing.
     * Excludes primitives, wrappers, strings, classes, and other non-manager types.
     *
     * 判断对象是否像一个值得探测的配方管理器。
     * 排除基本类型、包装类型、字符串、Class 等非管理器类型。
     */
    private static boolean isManagerCandidate(Object val) {
        return !(val instanceof Number) && !(val instanceof Boolean)
            && !(val instanceof String)
            && !(val instanceof Character)
            && !(val instanceof Class);
    }

    /**
     * Tries to call recipe-returning methods (getRecipes, getRecipeList, etc.)
     * on a manager-like object obtained from a static field.
     * Handles Forestry's pattern: RecipeManagers.carpenterManager.getRecipes()
     *
     * 尝试在从静态字段获取的管理器对象上调用返回配方的方法。
     * 处理林业的模式：RecipeManagers.carpenterManager.getRecipes()
     */
    private static int tryExtractFromManager(Object manager, String modLabel, String fieldPath) {
        int registered = 0;
        for (String mName : new String[] { "getRecipes", "getRecipeList", "getAllRecipes", "recipes", "getSmeltingList",
            "getCraftings" }) {
            try {
                Method m = manager.getClass()
                    .getMethod(mName);
                Object result = m.invoke(manager);
                if (result instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) result;
                    logReflect(modLabel, fieldPath + "." + mName + "() -> Map (size=" + map.size() + ")");
                    registered += processRecipeMap(map, modLabel);
                } else if (result instanceof List) {
                    List<?> list = (List<?>) result;
                    logReflect(modLabel, fieldPath + "." + mName + "() -> List (size=" + list.size() + ")");
                    registered += processRecipeList(list, modLabel);
                } else if (result instanceof Collection) {
                    List<?> list = new ArrayList<>((Collection<?>) result);
                    logReflect(modLabel, fieldPath + "." + mName + "() -> Collection (size=" + list.size() + ")");
                    registered += processRecipeList(list, modLabel);
                }
            } catch (NoSuchMethodException ignored) {} catch (Exception e) {
                logReflectError(modLabel, fieldPath + "." + mName + "()", e);
            }
        }
        return registered;
    }

    // ==================== Recipe Processing / 配方处理 ====================

    /**
     * Processes a Map that may contain input→output recipe pairs.
     * 处理可能包含输入→输出配方对的 Map。
     */
    private static int processRecipeMap(Map<?, ?> map, String modLabel) {
        int registered = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            try {
                ItemStack input = toItemStack(entry.getKey());
                Object val = entry.getValue();

                if (val instanceof ItemStack) {
                    registered += tryDeriveFromInput((ItemStack) val, input, modLabel);
                } else if (val instanceof ItemStack[]) {
                    for (ItemStack out : (ItemStack[]) val) {
                        registered += tryDeriveFromInput(out, input, modLabel);
                    }
                }
            } catch (Exception e) {
                logReflectError(modLabel, "processRecipeMap entry", e);
            }
        }
        return registered;
    }

    /**
     * Processes a List that may contain recipe objects with input/output fields.
     * Uses reflection to extract input and output ItemStacks from each element.
     * Each method probe attempt is logged.
     *
     * 处理可能包含配方对象的 List。
     * 使用反射从每个元素中提取输入和输出 ItemStack。
     * 每个方法探测尝试都会记录日志。
     */
    private static int processRecipeList(List<?> list, String modLabel) {
        int registered = 0;
        boolean loggedType = false;

        for (Object recipe : list) {
            if (recipe == null) continue;

            // Log the type of the first non-null element for debugging
            // 记录第一个非空元素的类型以便调试
            if (!loggedType) {
                logReflect(
                    modLabel,
                    "processRecipeList: element type = " + recipe.getClass()
                        .getName());
                loggedType = true;
            }

            try {
                ItemStack output = null;
                ItemStack input = null;
                List<ItemStack> inputs = new ArrayList<>();

                for (String mName : new String[] { "getOutput", "getRecipeOutput", "getResult" }) {
                    try {
                        Method m = recipe.getClass()
                            .getMethod(mName);
                        Object result = m.invoke(recipe);
                        if (result instanceof ItemStack) {
                            output = (ItemStack) result;
                            break;
                        }
                    } catch (NoSuchMethodException ignored) {
                        // Expected probe — not logged per-element to avoid spam
                    } catch (Exception e) {
                        logReflectError(
                            modLabel,
                            recipe.getClass()
                                .getSimpleName() + "."
                                + mName
                                + "()",
                            e);
                    }
                }

                for (String mName : new String[] { "getInput", "getRecipeInput", "getCatalyst", "getIngredient",
                    "getInputs", "getIngredients" }) {
                    try {
                        Method m = recipe.getClass()
                            .getMethod(mName);
                        Object result = m.invoke(recipe);
                        if (result instanceof ItemStack) {
                            input = (ItemStack) result;
                            break;
                        } else if (result instanceof ItemStack[]) {
                            for (ItemStack s : (ItemStack[]) result) {
                                if (s != null) inputs.add(s);
                            }
                            break;
                        } else if (result instanceof List) {
                            for (Object o : (List<?>) result) {
                                ItemStack s = toItemStack(o);
                                if (s != null) inputs.add(s);
                            }
                            break;
                        }
                    } catch (NoSuchMethodException ignored) {
                        // Expected probe
                    } catch (Exception e) {
                        logReflectError(
                            modLabel,
                            recipe.getClass()
                                .getSimpleName() + "."
                                + mName
                                + "()",
                            e);
                    }
                }

                if (output != null && output.getItem() != null) {
                    if (input != null) {
                        registered += tryDeriveFromInput(output, input, modLabel);
                    } else if (!inputs.isEmpty()) {
                        registered += tryDeriveFromInputs(output, inputs, modLabel);
                    } else {
                        // Output found but standard input probes failed.
                        // Try ritual-specific processing (handles getOfferings/getSacrifice).
                        // 找到产物但标准输入探测全部失败。
                        // 尝试仪式专用处理（处理 getOfferings/getSacrifice）。
                        registered += processRitualObject(recipe, modLabel);
                    }
                } else {
                    // Standard output probes failed — try ritual processing as fallback
                    // 标准产物探测失败 — 尝试仪式处理作为回退
                    String cn = recipe.getClass()
                        .getName()
                        .toLowerCase();
                    if (cn.contains("ritual") || cn.contains("necro")
                        || cn.contains("rite")
                        || hasRitualMethods(recipe)) {
                        registered += processRitualObject(recipe, modLabel);
                    }
                }
            } catch (Exception e) {
                logReflectError(
                    modLabel,
                    "processRecipeList element (" + recipe.getClass()
                        .getName() + ")",
                    e);
            }
        }
        return registered;
    }

    // ==================== Aspect Derivation / 要素推导 ====================

    /**
     * If the output item has no aspects yet, derive from a single input with RECIPE_DECAY (90% decay, min 1).
     * 如果输出物品还没有要素，则从单个输入推导并施加 RECIPE_DECAY（90% 衰减，至少 1 点）。
     */
    private static int tryDeriveFromInput(ItemStack output, ItemStack input, String modLabel) {
        if (output == null || output.getItem() == null) return 0;
        if (input == null || input.getItem() == null) return 0;
        if (AspectUtils.hasAspect(output)) return 0;

        AspectList inputAsp = AspectDeriver.getOrGenerateAspectsFor(input, 0, new HashSet<>());
        if (inputAsp == null || inputAsp.size() == 0) return 0;

        AspectList result = AspectUtils.scaleAspects(inputAsp, AspectUtils.RECIPE_DECAY);
        if (result == null || result.size() == 0) return 0;

        ThaumcraftApi.registerObjectTag(output, result.copy());
        AspectUtils.CACHE.put(AspectUtils.key(output), result.copy());
        AspectUtils.statNewlyRegistered++;

        String id = AspectUtils.key(output);
        String displayName;
        try {
            displayName = output.getDisplayName();
        } catch (Exception e) {
            displayName = "?";
        }
        ModFileLogger.scan(
            tr("[Mod recipes]") + " "
                + modLabel
                + " | "
                + id
                + " ("
                + displayName
                + ") <- "
                + AspectUtils.aspectListToString(result));
        AspectUtils.FAILED_IDS.remove(id.contains("@") ? id.substring(0, id.indexOf("@")) : id);
        return 1;
    }

    /**
     * Derive aspects from multiple inputs (RECIPE_DECAY: 90% decay, min 1), then register or merge with existing.
     * If the output has no aspects, register the derived list. If it has aspects, merge by
     * taking the maximum of each aspect (existing vs derived) so we never drop existing and
     * can improve weak ones.
     *
     * 从多个输入推导要素（50% 衰减），然后注册或与现有合并。
     * 若输出无要素则直接注册；若有则按每种要素取 max(现有, 推导) 合并，不覆盖且可增强弱要素。
     */
    private static int tryDeriveFromInputs(ItemStack output, List<ItemStack> inputs, String modLabel) {
        if (output == null || output.getItem() == null) return 0;

        AspectList combined = new AspectList();
        boolean hasAny = false;
        for (ItemStack input : inputs) {
            if (input == null || input.getItem() == null) continue;
            AspectList asp = AspectDeriver.getOrGenerateAspectsFor(input, 0, new HashSet<>());
            if (asp != null && asp.size() > 0) {
                combined.add(asp);
                hasAny = true;
            }
        }
        if (!hasAny) return 0;

        AspectList derived = AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY);
        if (derived == null || derived.size() == 0) return 0;

        AspectList result = derived.copy();
        if (AspectUtils.hasAspect(output)) {
            AspectList existing = ThaumcraftApiHelper.getObjectAspects(output);
            if (existing != null && existing.size() > 0) {
                result = AspectUtils.mergeAspectsMax(existing, derived);
            }
        }

        if (result == null || result.size() == 0) return 0;

        ThaumcraftApi.registerObjectTag(output, result.copy());
        AspectUtils.CACHE.put(AspectUtils.key(output), result.copy());
        AspectUtils.statNewlyRegistered++;

        String id = AspectUtils.key(output);
        String displayName;
        try {
            displayName = output.getDisplayName();
        } catch (Exception e) {
            displayName = "?";
        }
        ModFileLogger.scan(
            tr("[Mod recipes]") + " "
                + modLabel
                + " | "
                + id
                + " ("
                + displayName
                + ") <- "
                + AspectUtils.aspectListToString(result));
        AspectUtils.FAILED_IDS.remove(id.contains("@") ? id.substring(0, id.indexOf("@")) : id);
        return 1;
    }

    /**
     * Safely converts an arbitrary object to an ItemStack if possible.
     * 安全地将任意对象转换为 ItemStack（如果可能的话）。
     */
    private static ItemStack toItemStack(Object obj) {
        if (obj instanceof ItemStack) return (ItemStack) obj;
        return null;
    }

    /**
     * Converts Object to ItemStack. Handles ItemStack directly, and String as OreDict name
     * (returns the first registered ore for the name). Other types are ignored.
     *
     * 将 Object 转为 ItemStack。直接处理 ItemStack；String 视为矿辞名
     * （返回该矿辞的第一个注册物品）。其他类型忽略。
     */
    private static ItemStack objectToItemStack(Object obj) {
        if (obj == null) return null;
        if (obj instanceof ItemStack) return (ItemStack) obj;
        if (obj instanceof String) {
            String oreName = (String) obj;
            try {
                ArrayList<ItemStack> ores = OreDictionary.getOres(oreName);
                if (ores != null && !ores.isEmpty()) {
                    return ores.get(0);
                }
            } catch (Exception e) {
                // OreDict lookup failed, ignore
            }
            return null;
        }
        return null;
    }
}
