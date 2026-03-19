package com.sunmilktea.thaumicallaspect.aspect.modbridge;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.sunmilktea.thaumicallaspect.ThaumicAllAspect;
import com.sunmilktea.thaumicallaspect.aspect.derive.AspectDeriver;
import com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils;
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
 * <p>
 * Each mod's recipe system is accessed purely through reflection so there is no hard dependency.
 * If a mod isn't loaded, its scanner method silently returns zero results.
 * All reflection failures are logged to the scan log file for debugging.
 * <p>
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
 * <p>
 * 通过反射扫描其他模组的非标准配方系统，提取输入/输出对，
 * 为主 CraftingManager 扫描器遗漏的物品推导要素。
 * <p>
 * 每个模组的配方系统完全通过反射访问，因此没有硬依赖。
 * 如果模组未加载，其扫描方法会静默返回零结果。
 * 所有反射失败都会记录到扫描日志文件以便调试。
 * <p>
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
public class ModRecipeBridge {

    /**
     * Total items registered by all mod recipe bridges in a single scan.
     */
    public static int statModRecipeRegistered;

    /**
     * Runs one mod's scanner; on Throwable (e.g. NoClassDefFoundError from client-only class)
     * logs and returns 0 so server can still run other mods' scans.
     * 执行单个模组的扫描；若抛出 Throwable（如因客户端类导致 NoClassDefFoundError）则记录并返回 0，服务器可继续扫描其他模组。
     */
    private static int safeModScan(final String modName, final IntSupplier scan) {
        try {
            return scan.getAsInt();
        } catch (final NoClassDefFoundError e) {
            ModFileLogger.warn(
                "[ThaumicAllAspect] " + modName
                    + " "
                    + tr("scan skipped")
                    + " (incompatible/missing class): "
                    + (null != e.getMessage() ? e.getMessage()
                        : e.getClass()
                            .getSimpleName()));
            return 0;
        } catch (final Throwable t) {
            String msg = t.getClass()
                .getSimpleName();
            if (null != t.getMessage()) msg += ": " + t.getMessage();
            ModFileLogger.warn("[ThaumicAllAspect] " + modName + " " + tr("scan skipped") + ": " + msg);
            return 0;
        }
    }

    // ==================== Entry Point / 入口 ====================

    /**
     * Entry point: runs all supported mod recipe scanners with multi-pass retry.
     * <p>
     * Recipe outputs may depend on other mod-recipe outputs as inputs.
     * A single pass can miss items whose ingredients haven't been derived yet.
     * So we repeat the full scan until no new items are registered (convergence)
     * or the maximum number of passes is reached.
     * <p>
     * 入口：带多轮重试运行所有支持的模组配方扫描器。
     * <p>
     * 配方产物可能依赖其他模组配方的产物作为材料。
     * 单次扫描可能遗漏材料尚未推导的物品。
     * 因此重复完整扫描直到无新注册（收敛）或达到最大轮次。
     */
    public static void scanModSpecificRecipes() {
        if (Side.SERVER == FMLCommonHandler.instance()
            .getEffectiveSide()) {
            return; // 服务器依赖 config 缓存获得模组配方要素，不执行任何反射扫描
        }
        ModRecipeBridge.statModRecipeRegistered = 0;
        final long t0 = System.currentTimeMillis();
        final int maxPasses = 5;
        int totalCount = 0;

        for (int pass = 1; pass <= maxPasses; pass++) {
            int passCount = 0;
            if (Loader.isModLoaded("abyssalcraft"))
                passCount += ModRecipeBridge.safeModScan("AbyssalCraft", ModRecipeBridge::scanAbyssalCraft);
            if (Loader.isModLoaded("witchery"))
                passCount += ModRecipeBridge.safeModScan("Witchery", ModRecipeBridge::scanWitchery);
            if (Loader.isModLoaded("bloodmagic"))
                passCount += ModRecipeBridge.safeModScan("Blood Magic", ModRecipeBridge::scanBloodMagic);
            if (Loader.isModLoaded("botania"))
                passCount += ModRecipeBridge.safeModScan("Botania", ModRecipeBridge::scanBotania);
            if (Loader.isModLoaded("forestry"))
                passCount += ModRecipeBridge.safeModScan("Forestry", ModRecipeBridge::scanForestry);
            if (Loader.isModLoaded("tconstruct"))
                passCount += ModRecipeBridge.safeModScan("Tinkers' Construct", ModRecipeBridge::scanTinkersConstruct);
            if (Loader.isModLoaded("enderio"))
                passCount += ModRecipeBridge.safeModScan("EnderIO", ModRecipeBridge::scanEnderIO);
            if (Loader.isModLoaded("railcraft"))
                passCount += ModRecipeBridge.safeModScan("Railcraft", ModRecipeBridge::scanRailcraft);

            totalCount += passCount;

            final String passMsg = tr("[Mod recipes]") + " "
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

            if (0 == passCount) {
                final String stopMsg = tr("[Mod recipes]") + " "
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

        final long elapsed = System.currentTimeMillis() - t0;
        if (0 < totalCount) {
            final String msg = tr("[Mod recipes]") + " "
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
        ModRecipeBridge.statModRecipeRegistered = totalCount;
    }

    // ==================== Utility Helpers / 工具方法 ====================

    /**
     * Tries to load a class by name from multiple candidates. Returns null if none found.
     * ClassNotFoundException is expected (mod not installed), so it is NOT logged.
     * <p>
     * 尝试按名称从多个候选项加载类。如果都找不到则返回 null。
     * ClassNotFoundException 是预期的（模组未安装），因此不记录日志。
     */
    private static Class<?> tryLoadClass(final String... classNames) {
        for (final String name : classNames) {
            try {
                return Class.forName(name);
            } catch (final ClassNotFoundException ignored) {}
        }
        return null;
    }

    /**
     * Tries to extract recipes from a class by name. Returns 0 if class not found.
     * 尝试按类名提取配方。如果类找不到则返回 0。
     */
    private static int tryExtractFromClass(final String className, final String modLabel) {
        try {
            final Class<?> clazz = Class.forName(className);
            ModRecipeBridge.logReflect(modLabel, "class loaded: " + className);
            return ModRecipeBridge.extractRecipesFromClass(clazz, modLabel);
        } catch (final ClassNotFoundException e) {
            ModRecipeBridge.logReflect(modLabel, "class not found: " + className);
            return 0;
        }
    }

    /**
     * Logs a reflection event to the scan log file for debugging.
     * All reflection attempts (success and failure) are recorded here, not in console.
     * <p>
     * 将反射事件记录到扫描日志文件以便调试。
     * 所有反射尝试（成功和失败）都记录在这里，不在控制台输出。
     */
    private static void logReflect(final String context, final String detail) {
        ModFileLogger.scan("[Reflect] " + context + " | " + detail);
    }

    /**
     * Logs a reflection exception to the scan log file.
     * 将反射异常记录到扫描日志文件。
     */
    private static void logReflectError(final String context, final String operation, final Exception e) {
        String msg = e.getClass()
            .getSimpleName();
        if (null != e.getMessage()) msg += ": " + e.getMessage();
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
     * <p>
     * The ritual API was added in AbyssalCraft mod version 1.4 (@since 1.4).
     * The 1.7.10 GitHub branch is an early snapshot; actual released JARs include rituals.
     * <p>
     * 扫描深渊国度的 5 种配方系统（通过公开单例 API，1.7.10 版本）：
     * 1. 变质器 — Map(输入->输出)
     * 2. 结晶器 — Map(输入->ItemStack[]{输出1,输出2})
     * 3. 物化器 — List(Materialization{输入数组,输出})
     * 4. 雕刻器 — Map(模板->硬币)
     * 5. 死灵之书仪式（NecronomiconCreationRitual / NecronomiconInfusionRitual）：
     * - 输出: getItem() -> ItemStack
     * - 供品: getOfferings() -> Object[]（ItemStack 或 String/矿辞名）
     * - 祭品: getSacrifice() -> Object（ItemStack 或 String/矿辞名）
     * <p>
     * 仪式 API 从深渊国度 mod 版本 1.4 起加入。
     * GitHub 上的 1.7.10 分支只是早期快照，实际发布的 JAR 包含仪式系统。
     */
    private static int scanAbyssalCraft() {
        final Class<?> apiClass = ModRecipeBridge.tryLoadClass(
            "com.shinoow.abyssalcraft.api.AbyssalCraftAPI",
            "com.shinoow.abyssalcraft.api.recipe.CrystallizerRecipes");
        if (null == apiClass) return 0;

        int registered = 0;
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " AbyssalCraft " + tr("detected, scanning"));
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== AbyssalCraft ==========");

        registered += ModRecipeBridge.scanACTransmutator();
        registered += ModRecipeBridge.scanACCrystallizer();
        registered += ModRecipeBridge.scanACMaterializer();
        registered += ModRecipeBridge.scanACEngraver();
        registered += ModRecipeBridge.scanACRituals();

        ModRecipeBridge.logModSummary("AbyssalCraft", registered);
        return registered;
    }

    /**
     * TransmutatorRecipes: Map(ItemStack input → ItemStack output)
     * 变质器配方：Map(输入 → 输出)
     */
    private static int scanACTransmutator() {
        try {
            final Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.TransmutatorRecipes");
            final Method inst = clazz.getMethod("instance");
            final Object singleton = inst.invoke(null);
            final Method getList = clazz.getMethod("getTransmutationList");
            final Object result = getList.invoke(singleton);
            if (result instanceof Map) {
                final Map<?, ?> map = (Map<?, ?>) result;
                ModRecipeBridge.logReflect(
                    "AbyssalCraft",
                    "TransmutatorRecipes.getTransmutationList() -> Map (size=" + map.size() + ")");
                int reg = 0;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    final ItemStack input = ModRecipeBridge.toItemStack(entry.getKey());
                    final ItemStack output = ModRecipeBridge.toItemStack(entry.getValue());
                    reg += ModRecipeBridge.tryDeriveFromInput(output, input, "AbyssalCraft-Transmutator");
                }
                ModRecipeBridge.logReflect("AbyssalCraft", "Transmutator: derived " + reg + " items");
                return reg;
            }
        } catch (final ClassNotFoundException e) {
            ModRecipeBridge.logReflect("AbyssalCraft", "TransmutatorRecipes class not found");
        } catch (final Exception e) {
            ModRecipeBridge.logReflectError("AbyssalCraft", "scanACTransmutator", e);
        }
        return 0;
    }

    /**
     * CrystallizerRecipes: Map(ItemStack input → ItemStack[] {output1, output2})
     * 结晶器配方：Map(输入 → ItemStack[]{输出1, 输出2})
     */
    private static int scanACCrystallizer() {
        try {
            final Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.CrystallizerRecipes");
            final Method inst = clazz.getMethod("instance");
            final Object singleton = inst.invoke(null);
            final Method getList = clazz.getMethod("getCrystallizationList");
            final Object result = getList.invoke(singleton);
            if (result instanceof Map) {
                final Map<?, ?> map = (Map<?, ?>) result;
                ModRecipeBridge.logReflect(
                    "AbyssalCraft",
                    "CrystallizerRecipes.getCrystallizationList() -> Map (size=" + map.size() + ")");
                int reg = 0;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    final ItemStack input = ModRecipeBridge.toItemStack(entry.getKey());
                    if (null == input || null == input.getItem()) continue;
                    final Object val = entry.getValue();
                    if (val instanceof ItemStack[]) {
                        for (final ItemStack output : (ItemStack[]) val) {
                            if (null != output && null != output.getItem()) {
                                reg += ModRecipeBridge.tryDeriveFromInput(output, input, "AbyssalCraft-Crystallizer");
                            }
                        }
                    }
                }
                ModRecipeBridge.logReflect("AbyssalCraft", "Crystallizer: derived " + reg + " items");
                return reg;
            }
        } catch (final ClassNotFoundException e) {
            ModRecipeBridge.logReflect("AbyssalCraft", "CrystallizerRecipes class not found");
        } catch (final Exception e) {
            ModRecipeBridge.logReflectError("AbyssalCraft", "scanACCrystallizer", e);
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
            final Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.MaterializerRecipes");
            final Method inst = clazz.getMethod("instance");
            final Object singleton = inst.invoke(null);
            final Method getList = clazz.getMethod("getMaterializationList");
            final Object result = getList.invoke(singleton);
            if (result instanceof List) {
                final List<?> list = (List<?>) result;
                ModRecipeBridge.logReflect(
                    "AbyssalCraft",
                    "MaterializerRecipes.getMaterializationList() -> List (size=" + list.size() + ")");
                int reg = 0;
                for (final Object mat : list) {
                    if (null == mat) continue;
                    try {
                        final Field outputField = mat.getClass()
                            .getField("output");
                        final Field inputField = mat.getClass()
                            .getField("input");
                        final ItemStack output = (ItemStack) outputField.get(mat);
                        final ItemStack[] inputs = (ItemStack[]) inputField.get(mat);
                        if (null == output || null == output.getItem()) continue;
                        if (null == inputs || 0 == inputs.length) continue;
                        final List<ItemStack> inputList = new ArrayList<>();
                        for (final ItemStack s : inputs) {
                            if (null != s && null != s.getItem()) inputList.add(s);
                        }
                        if (!inputList.isEmpty()) {
                            reg += ModRecipeBridge.tryDeriveFromInputs(output, inputList, "AbyssalCraft-Materializer");
                        }
                    } catch (final Exception e) {
                        ModRecipeBridge.logReflectError("AbyssalCraft", "Materialization field access", e);
                    }
                }
                ModRecipeBridge.logReflect("AbyssalCraft", "Materializer: derived " + reg + " items");
                return reg;
            }
        } catch (final ClassNotFoundException e) {
            ModRecipeBridge.logReflect("AbyssalCraft", "MaterializerRecipes class not found");
        } catch (final Exception e) {
            ModRecipeBridge.logReflectError("AbyssalCraft", "scanACMaterializer", e);
        }
        return 0;
    }

    /**
     * EngraverRecipes: Map(ItemStack template → ItemStack coin)
     * 雕刻器配方：Map(模板 → 硬币)
     */
    private static int scanACEngraver() {
        try {
            final Class<?> clazz = Class.forName("com.shinoow.abyssalcraft.api.recipe.EngraverRecipes");
            final Method inst = clazz.getMethod("instance");
            final Object singleton = inst.invoke(null);
            final Method getList = clazz.getMethod("getEngravingList");
            final Object result = getList.invoke(singleton);
            if (result instanceof Map) {
                final Map<?, ?> map = (Map<?, ?>) result;
                ModRecipeBridge
                    .logReflect("AbyssalCraft", "EngraverRecipes.getEngravingList() -> Map (size=" + map.size() + ")");
                int reg = 0;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    final ItemStack input = ModRecipeBridge.toItemStack(entry.getKey());
                    final ItemStack output = ModRecipeBridge.toItemStack(entry.getValue());
                    reg += ModRecipeBridge.tryDeriveFromInput(output, input, "AbyssalCraft-Engraver");
                }
                ModRecipeBridge.logReflect("AbyssalCraft", "Engraver: derived " + reg + " items");
                return reg;
            }
        } catch (final ClassNotFoundException e) {
            ModRecipeBridge.logReflect("AbyssalCraft", "EngraverRecipes class not found");
        } catch (final Exception e) {
            ModRecipeBridge.logReflectError("AbyssalCraft", "scanACEngraver", e);
        }
        return 0;
    }

    /**
     * Scans AbyssalCraft Necronomicon Rituals via reflection.
     * Ritual classes: NecronomiconCreationRitual, NecronomiconInfusionRitual
     * (both extend NecronomiconRitual, @since mod version 1.4).
     * <p>
     * Strategy:
     * 1. Try to find a ritual registry via AbyssalCraftAPI static fields/methods
     * (e.g. getRituals(), rituals field, internal method handler).
     * 2. If no direct registry, probe the API class's static fields for List/Map
     * containing ritual objects (class name contains "Ritual").
     * 3. For each ritual object, delegate to processRitualObject() which tries:
     * getItem() for output, getOfferings() for Object[] inputs, getSacrifice() for Object input.
     * <p>
     * 通过反射扫描深渊国度死灵之书仪式。
     * 策略：
     * 1. 尝试通过 AbyssalCraftAPI 的静态字段/方法找到仪式注册表
     * 2. 如果没有直接的注册表，探测 API 类的静态字段寻找包含仪式对象的 List/Map
     * 3. 对每个仪式对象调用 processRitualObject()
     */
    private static int scanACRituals() {
        int registered = 0;
        final List<Object> ritualObjs = new ArrayList<>();

        try {
            final Class<?> apiClass = Class.forName("com.shinoow.abyssalcraft.api.AbyssalCraftAPI");

            // Phase 0: Dump all static methods and fields for diagnostics (skipped when skipDiagnosticDumps)
            // 阶段0：转储所有静态方法和字段用于诊断（配置 skipDiagnosticDumps 时跳过以加快加载）
            if (!ThaumicAllAspect.skipDiagnosticDumps) {
                ModRecipeBridge.logReflect("AbyssalCraft", "--- API class dump (methods) ---");
                for (final Method m : apiClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) {
                        ModRecipeBridge.logReflect(
                            "AbyssalCraft",
                            "  static method: " + m.getName()
                                + "() -> "
                                + m.getReturnType()
                                    .getSimpleName());
                    }
                }
                ModRecipeBridge.logReflect("AbyssalCraft", "--- API class dump (fields) ---");
                for (final Field f : apiClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        final String typeName = f.getType()
                            .getSimpleName();
                        String valInfo = "";
                        try {
                            f.setAccessible(true);
                            final Object val = f.get(null);
                            if (val instanceof List) {
                                final List<?> list = (List<?>) val;
                                valInfo = " (List size=" + list.size();
                                if (!list.isEmpty() && null != list.get(0)) {
                                    valInfo += ", element[0]=" + list.get(0)
                                        .getClass()
                                        .getName();
                                }
                                valInfo += ")";
                            } else if (val instanceof Map) {
                                valInfo = " (Map size=" + ((Map<?, ?>) val).size() + ")";
                            } else if (null != val) {
                                valInfo = " = " + val.getClass()
                                    .getName();
                            } else {
                                valInfo = " = null";
                            }
                        } catch (final Exception ex) {
                            valInfo = " (access error: " + ex.getMessage() + ")";
                        }
                        ModRecipeBridge
                            .logReflect("AbyssalCraft", "  static field: " + f.getName() + " : " + typeName + valInfo);
                    }
                }
                ModRecipeBridge.logReflect("AbyssalCraft", "--- end API dump ---");
            }

            // Phase 1: Try direct getter methods for ritual lists
            for (final String mName : new String[] { "getRituals", "getCreationRituals", "getInfusionRituals",
                "getNecronomiconRituals", "getRitualList" }) {
                try {
                    final Method m = apiClass.getMethod(mName);
                    if (Modifier.isStatic(m.getModifiers())) {
                        final Object result = m.invoke(null);
                        if (result instanceof List) {
                            final List<?> list = (List<?>) result;
                            ModRecipeBridge.logReflect(
                                "AbyssalCraft",
                                "AbyssalCraftAPI." + mName + "() -> List (size=" + list.size() + ")");
                            for (final Object obj : list) {
                                if (null != obj) ritualObjs.add(obj);
                            }
                        } else if (result instanceof Map) {
                            final Map<?, ?> map = (Map<?, ?>) result;
                            ModRecipeBridge.logReflect(
                                "AbyssalCraft",
                                "AbyssalCraftAPI." + mName + "() -> Map (size=" + map.size() + ")");
                            for (final Object obj : map.values()) {
                                if (null != obj) ritualObjs.add(obj);
                            }
                        }
                    }
                } catch (final NoSuchMethodException ignored) {
                    ModRecipeBridge.logReflect("AbyssalCraft", "AbyssalCraftAPI." + mName + "() not found");
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError("AbyssalCraft", "AbyssalCraftAPI." + mName + "()", e);
                }
            }

            // Phase 2: Scan ALL static List/Map/array fields for ritual-like objects
            if (ritualObjs.isEmpty()) {
                ModRecipeBridge.logReflect("AbyssalCraft", "No direct ritual getter found, scanning static fields...");
                for (final Field f : apiClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        final Object val = f.get(null);
                        if (val instanceof List) {
                            final List<?> list = (List<?>) val;
                            for (final Object obj : list) {
                                if (null != obj && ModRecipeBridge.isRitualLike(obj)) {
                                    ritualObjs.add(obj);
                                }
                            }
                            if (!ritualObjs.isEmpty()) {
                                ModRecipeBridge.logReflect(
                                    "AbyssalCraft",
                                    "Found " + ritualObjs.size() + " ritual objects in field '" + f.getName() + "'");
                            }
                        } else if (val instanceof Map) {
                            final Map<?, ?> map = (Map<?, ?>) val;
                            for (final Object obj : map.values()) {
                                if (null != obj && ModRecipeBridge.isRitualLike(obj)) {
                                    ritualObjs.add(obj);
                                }
                            }
                            if (!ritualObjs.isEmpty()) {
                                ModRecipeBridge.logReflect(
                                    "AbyssalCraft",
                                    "Found " + ritualObjs.size() + " ritual objects in field '" + f.getName() + "'");
                            }
                        } else if (null != val && val.getClass()
                            .isArray()) {
                                final Object[] arr = (Object[]) val;
                                for (final Object obj : arr) {
                                    if (null != obj && ModRecipeBridge.isRitualLike(obj)) {
                                        ritualObjs.add(obj);
                                    }
                                }
                                if (!ritualObjs.isEmpty()) {
                                    ModRecipeBridge.logReflect(
                                        "AbyssalCraft",
                                        "Found " + ritualObjs.size()
                                            + " ritual objects in array field '"
                                            + f.getName()
                                            + "'");
                                }
                            }
                    } catch (final Exception e) {
                        ModRecipeBridge.logReflectError("AbyssalCraft", "field " + f.getName(), e);
                    }
                }
            }

            // Phase 3: Try internal method handler
            if (ritualObjs.isEmpty()) {
                try {
                    final Method getHandler = apiClass.getMethod("getInternalMethodHandler");
                    if (Modifier.isStatic(getHandler.getModifiers())) {
                        final Object handler = getHandler.invoke(null);
                        if (null != handler) {
                            ModRecipeBridge.logReflect(
                                "AbyssalCraft",
                                "Got InternalMethodHandler: " + handler.getClass()
                                    .getName());
                            for (final String mName : new String[] { "getRituals", "getAllRituals", "getRitualList" }) {
                                try {
                                    final Method m = handler.getClass()
                                        .getMethod(mName);
                                    final Object result = m.invoke(handler);
                                    if (result instanceof List) {
                                        final List<?> list = (List<?>) result;
                                        for (final Object obj : list) {
                                            if (null != obj) ritualObjs.add(obj);
                                        }
                                        ModRecipeBridge.logReflect(
                                            "AbyssalCraft",
                                            "handler." + mName + "() -> " + ritualObjs.size() + " rituals");
                                    }
                                } catch (final NoSuchMethodException ignored) {} catch (final Exception e) {
                                    ModRecipeBridge.logReflectError("AbyssalCraft", "handler." + mName + "()", e);
                                }
                            }
                            if (ritualObjs.isEmpty()) {
                                for (final Field f : handler.getClass()
                                    .getDeclaredFields()) {
                                    try {
                                        f.setAccessible(true);
                                        final Object val = f.get(handler);
                                        if (val instanceof List) {
                                            for (final Object obj : (List<?>) val) {
                                                if (null != obj && ModRecipeBridge.isRitualLike(obj)) {
                                                    ritualObjs.add(obj);
                                                }
                                            }
                                        }
                                    } catch (final Exception ignored) {}
                                }
                                if (!ritualObjs.isEmpty()) {
                                    ModRecipeBridge.logReflect(
                                        "AbyssalCraft",
                                        "Found " + ritualObjs.size() + " ritual objects in handler fields");
                                }
                            }
                        }
                    }
                } catch (final NoSuchMethodException ignored) {
                    ModRecipeBridge.logReflect("AbyssalCraft", "getInternalMethodHandler() not found");
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError("AbyssalCraft", "getInternalMethodHandler()", e);
                }
            }

            // Phase 4: RitualRegistry singleton — the confirmed location of ritual data.
            // RitualRegistry has a static 'instance' field (singleton pattern).
            // Rituals are stored inside this instance, not as a static List on the class.
            // RitualRegistry 是单例模式，仪式列表存储在实例的内部字段中，而非类的静态 List。
            if (ritualObjs.isEmpty()) {
                ModRecipeBridge.logReflect("AbyssalCraft", "Phase 4: Scanning RitualRegistry singleton...");
                try {
                    final Class<?> registryClass = Class.forName("com.shinoow.abyssalcraft.api.ritual.RitualRegistry");
                    Object registry = null;

                    try {
                        final Field instField = registryClass.getDeclaredField("instance");
                        instField.setAccessible(true);
                        registry = instField.get(null);
                        ModRecipeBridge.logReflect(
                            "AbyssalCraft",
                            "RitualRegistry.instance -> " + (null != registry ? registry.getClass()
                                .getName() : "null"));
                    } catch (final NoSuchFieldException e) {
                        ModRecipeBridge.logReflect(
                            "AbyssalCraft",
                            "RitualRegistry.instance field not found, trying getInstance()");
                        try {
                            final Method m = registryClass.getMethod("getInstance");
                            registry = m.invoke(null);
                        } catch (final NoSuchMethodException ignored) {}
                    }

                    if (null != registry) {
                        if (!ThaumicAllAspect.skipDiagnosticDumps) {
                            ModRecipeBridge.logReflect("AbyssalCraft", "--- RitualRegistry instance methods ---");
                            for (final Method m : registry.getClass()
                                .getDeclaredMethods()) {
                                ModRecipeBridge.logReflect(
                                    "AbyssalCraft",
                                    "  method: " + m.getName()
                                        + "() -> "
                                        + m.getReturnType()
                                            .getSimpleName());
                            }
                            ModRecipeBridge.logReflect("AbyssalCraft", "--- RitualRegistry instance fields ---");
                            for (final Field f : registry.getClass()
                                .getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    final Object val = Modifier.isStatic(f.getModifiers()) ? f.get(null)
                                        : f.get(registry);
                                    String info = f.getName() + " : "
                                        + f.getType()
                                            .getSimpleName();
                                    if (val instanceof List) {
                                        final List<?> list = (List<?>) val;
                                        info += " (List size=" + list.size();
                                        if (!list.isEmpty() && null != list.get(0)) {
                                            info += ", element[0]=" + list.get(0)
                                                .getClass()
                                                .getName();
                                        }
                                        info += ")";
                                    } else if (val instanceof Map) {
                                        info += " (Map size=" + ((Map<?, ?>) val).size() + ")";
                                    } else if (null != val) {
                                        info += " = " + val.getClass()
                                            .getName();
                                    }
                                    ModRecipeBridge.logReflect("AbyssalCraft", "  field: " + info);
                                } catch (final Exception ex) {
                                    ModRecipeBridge.logReflect(
                                        "AbyssalCraft",
                                        "  field: " + f.getName() + " (error: " + ex.getMessage() + ")");
                                }
                            }
                            ModRecipeBridge.logReflect("AbyssalCraft", "--- end RitualRegistry dump ---");
                        }

                        // Try getter methods on the singleton
                        for (final String mName : new String[] { "getRituals", "getRecipes", "getRitualList",
                            "getAllRituals", "getCreationRituals", "getInfusionRituals", "getRegisteredRituals" }) {
                            try {
                                final Method m = registry.getClass()
                                    .getMethod(mName);
                                final Object result = m.invoke(registry);
                                if (result instanceof List) {
                                    final List<?> list = (List<?>) result;
                                    ModRecipeBridge.logReflect(
                                        "AbyssalCraft",
                                        "RitualRegistry." + mName + "() -> List (size=" + list.size() + ")");
                                    for (final Object obj : list) {
                                        if (null != obj) ritualObjs.add(obj);
                                    }
                                    if (!ritualObjs.isEmpty()) break;
                                } else if (result instanceof Map) {
                                    final Map<?, ?> map = (Map<?, ?>) result;
                                    ModRecipeBridge.logReflect(
                                        "AbyssalCraft",
                                        "RitualRegistry." + mName + "() -> Map (size=" + map.size() + ")");
                                    for (final Object obj : map.values()) {
                                        if (null != obj) ritualObjs.add(obj);
                                    }
                                    if (!ritualObjs.isEmpty()) break;
                                }
                            } catch (final NoSuchMethodException ignored) {} catch (final Exception e) {
                                ModRecipeBridge.logReflectError("AbyssalCraft", "RitualRegistry." + mName + "()", e);
                            }
                        }

                        // Scan instance fields for List/Map containing ritual-like objects
                        if (ritualObjs.isEmpty()) {
                            ModRecipeBridge.logReflect("AbyssalCraft", "No getter found, scanning instance fields...");
                            for (final Field f : registry.getClass()
                                .getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    final Object val = Modifier.isStatic(f.getModifiers()) ? f.get(null)
                                        : f.get(registry);
                                    if (val instanceof List) {
                                        final List<?> list = (List<?>) val;
                                        if (!list.isEmpty()) {
                                            final Object first = list.get(0);
                                            if (null != first && ModRecipeBridge.isRitualLike(first)) {
                                                for (final Object obj : list) {
                                                    if (null != obj) ritualObjs.add(obj);
                                                }
                                                ModRecipeBridge.logReflect(
                                                    "AbyssalCraft",
                                                    "Found " + ritualObjs.size()
                                                        + " rituals in field '"
                                                        + f.getName()
                                                        + "'");
                                                break;
                                            }
                                        }
                                    } else if (val instanceof Map) {
                                        final Map<?, ?> map = (Map<?, ?>) val;
                                        for (final Object obj : map.values()) {
                                            if (null != obj && ModRecipeBridge.isRitualLike(obj)) {
                                                ritualObjs.add(obj);
                                            }
                                        }
                                        if (!ritualObjs.isEmpty()) {
                                            ModRecipeBridge.logReflect(
                                                "AbyssalCraft",
                                                "Found " + ritualObjs.size()
                                                    + " rituals in map field '"
                                                    + f.getName()
                                                    + "'");
                                            break;
                                        }
                                    }
                                } catch (final Exception ignored) {}
                            }
                        }

                        // Last resort: collect ALL objects from any non-empty List field
                        if (ritualObjs.isEmpty()) {
                            ModRecipeBridge
                                .logReflect("AbyssalCraft", "No ritual-like fields, scanning ALL List fields...");
                            for (final Field f : registry.getClass()
                                .getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    final Object val = Modifier.isStatic(f.getModifiers()) ? f.get(null)
                                        : f.get(registry);
                                    if (val instanceof List) {
                                        final List<?> list = (List<?>) val;
                                        if (!list.isEmpty()) {
                                            for (final Object obj : list) {
                                                if (null != obj) ritualObjs.add(obj);
                                            }
                                            ModRecipeBridge.logReflect(
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
                                } catch (final Exception ignored) {}
                            }
                        }
                    } else {
                        ModRecipeBridge.logReflect("AbyssalCraft", "RitualRegistry singleton is null");
                    }
                } catch (final ClassNotFoundException e) {
                    ModRecipeBridge.logReflect("AbyssalCraft", "RitualRegistry class not found");
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError("AbyssalCraft", "Phase 4: RitualRegistry", e);
                }
            }
        } catch (final ClassNotFoundException e) {
            ModRecipeBridge.logReflect("AbyssalCraft", "AbyssalCraftAPI class not found for ritual scanning");
            return 0;
        } catch (final Exception e) {
            ModRecipeBridge.logReflectError("AbyssalCraft", "scanACRituals", e);
        }

        // Process all collected ritual objects
        if (ritualObjs.isEmpty()) {
            ModRecipeBridge.logReflect("AbyssalCraft", "No Necronomicon ritual objects found");
        } else {
            ModRecipeBridge.logReflect("AbyssalCraft", "Processing " + ritualObjs.size() + " ritual objects...");
            for (final Object ritual : ritualObjs) {
                try {
                    registered += ModRecipeBridge.processRitualObject(ritual, "AbyssalCraft-Ritual");
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError(
                        "AbyssalCraft",
                        "processRitualObject for " + ritual.getClass()
                            .getName(),
                        e);
                }
            }
            ModRecipeBridge.logReflect("AbyssalCraft", "Rituals: derived " + registered + " items");
        }

        return registered;
    }

    /**
     * Checks if an object looks like a ritual (class name contains "ritual", "rite", "necro",
     * or it has ritual-like methods such as getOfferings/getSacrifice/getItem).
     * <p>
     * 检查对象是否看起来像仪式（类名包含 ritual/rite/necro，
     * 或具有 getOfferings/getSacrifice/getItem 等仪式方法）。
     */
    private static boolean isRitualLike(final Object obj) {
        final String cn = obj.getClass()
            .getName()
            .toLowerCase();
        if (cn.contains("ritual") || cn.contains("rite") || cn.contains("necro")) return true;
        return ModRecipeBridge.hasRitualMethods(obj);
    }

    // ==================== Witchery (巫术) ====================

    /**
     * Scans Witchery's recipe systems: Kettle (大釜), Distillery (蒸馏器),
     * Spinning Wheel (纺车), Oven (巫术烤炉), and Rite rituals (仪式).
     * <p>
     * Witchery stores recipes in singleton managers or static registries.
     * Kettle/Distillery recipes typically have getOutput()/getInputs() methods.
     * Rites are ritual-like objects with offerings + output.
     * <p>
     * 扫描巫术的配方系统：大釜、蒸馏器、纺车、巫术烤炉和仪式。
     * 巫术将配方存储在单例管理器或静态注册表中。
     */
    private static int scanWitchery() {
        final Class<?> detected = ModRecipeBridge
            .tryLoadClass("com.emoniph.witchery.Witchery", "com.emoniph.witchery.WitcheryAPI");
        if (null == detected) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Witchery ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Witchery " + tr("detected, scanning"));

        // Kettle recipes (大釜 — the main Witchery crafting station)
        // 大釜配方（巫术的主要合成站）
        final String[] kettleClasses = { "com.emoniph.witchery.crafting.KettleRecipes",
            "com.emoniph.witchery.brewing.KettleRecipes", "com.emoniph.witchery.api.KettleRecipes" };
        for (final String c : kettleClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "Witchery-Kettle");

        // Distillery (蒸馏器)
        final String[] distilleryClasses = { "com.emoniph.witchery.crafting.DistilleryRecipes",
            "com.emoniph.witchery.api.DistilleryRecipes" };
        for (final String c : distilleryClasses)
            registered += ModRecipeBridge.tryExtractFromClass(c, "Witchery-Distillery");

        // Spinning Wheel (纺车)
        final String[] spinningClasses = { "com.emoniph.witchery.crafting.SpinningRecipes",
            "com.emoniph.witchery.api.SpinningRecipes" };
        for (final String c : spinningClasses)
            registered += ModRecipeBridge.tryExtractFromClass(c, "Witchery-Spinning");

        // Oven (巫术烤炉)
        final String[] ovenClasses = { "com.emoniph.witchery.crafting.WitchesOvenRecipes",
            "com.emoniph.witchery.crafting.OvenRecipes" };
        for (final String c : ovenClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "Witchery-Oven");

        // Rite / Ritual system (仪式系统)
        // Witchery's rites are stored in a registry; each rite may have offerings + output
        // 巫术的仪式存储在注册表中；每个仪式可能有祭品和产物
        final String[] riteClasses = { "com.emoniph.witchery.ritual.RiteRegistry", "com.emoniph.witchery.ritual.Rites",
            "com.emoniph.witchery.api.RiteRegistry" };
        for (final String className : riteClasses) {
            try {
                final Class<?> riteClass = Class.forName(className);
                ModRecipeBridge.logReflect("Witchery-Rite", "loaded rite class: " + className);
                final List<Object> ritualObjs = new ArrayList<>();
                ModRecipeBridge.collectRitualObjectsFromClass(riteClass, ritualObjs, "Witchery-Rite");
                for (final Object rite : ritualObjs) {
                    if (null == rite) continue;
                    try {
                        registered += ModRecipeBridge.processRitualObject(rite, "Witchery-Rite");
                    } catch (final Exception e) {
                        ModRecipeBridge.logReflectError("Witchery-Rite", "processRitualObject", e);
                    }
                }
                registered += ModRecipeBridge.extractRecipesFromClass(riteClass, "Witchery-Rite");
            } catch (final ClassNotFoundException e) {
                ModRecipeBridge.logReflect("Witchery-Rite", "class not found: " + className);
            }
        }

        // Infusion (灌注)
        final String[] infusionClasses = { "com.emoniph.witchery.infusion.InfusionRecipes",
            "com.emoniph.witchery.crafting.InfusionRecipes" };
        for (final String c : infusionClasses)
            registered += ModRecipeBridge.tryExtractFromClass(c, "Witchery-Infusion");

        ModRecipeBridge.logModSummary("Witchery", registered);
        return registered;
    }

    // ==================== Blood Magic (血魔法) ====================

    /**
     * Scans Blood Magic's recipe systems: Blood Altar (血祭坛), Alchemy Table (炼金术台),
     * and Binding rituals (绑定仪式).
     * <p>
     * Blood Magic uses static registries in its API package. Altar recipes map
     * input→output with tier/LP requirements. Alchemy recipes have input arrays.
     * <p>
     * 扫描血魔法的配方系统：血祭坛、炼金术台和绑定仪式。
     * 血魔法在其 API 包中使用静态注册表。
     */
    private static int scanBloodMagic() {
        final Class<?> detected = ModRecipeBridge.tryLoadClass(
            "WayofTime.alchemicalWizardry.api.BloodMagicAPI",
            "WayofTime.alchemicalWizardry.BloodMagicAPI",
            "WayofTime.alchemicalWizardry.ModBloodMagic",
            "WayofTime.alchemicalWizardry.AlchemicalWizardry");
        if (null == detected) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Blood Magic ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Blood Magic " + tr("detected, scanning"));

        // Blood Altar recipes (血祭坛配方)
        final String[] altarClasses = { "WayofTime.alchemicalWizardry.api.altarRecipe.AltarRecipeRegistry",
            "WayofTime.alchemicalWizardry.api.altar.AltarRecipeRegistry",
            "WayofTime.alchemicalWizardry.common.AltarRecipeRegistry" };
        for (final String c : altarClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "BloodMagic-Altar");

        // Alchemy recipes (炼金术配方)
        final String[] alchemyClasses = { "WayofTime.alchemicalWizardry.api.alchemy.AlchemyRecipeRegistry",
            "WayofTime.alchemicalWizardry.api.AlchemyRecipeRegistry" };
        for (final String c : alchemyClasses)
            registered += ModRecipeBridge.tryExtractFromClass(c, "BloodMagic-Alchemy");

        // Binding recipes (绑定配方)
        final String[] bindingClasses = { "WayofTime.alchemicalWizardry.api.bindingRecipe.BindingRecipeRegistry",
            "WayofTime.alchemicalWizardry.api.binding.BindingRecipeRegistry" };
        for (final String c : bindingClasses)
            registered += ModRecipeBridge.tryExtractFromClass(c, "BloodMagic-Binding");

        // Also try the main API class fields/methods
        // 也尝试主 API 类的字段/方法
        registered += ModRecipeBridge.extractRecipesFromClass(detected, "BloodMagic");

        // Ritual-like objects (仪式类对象)
        final String[] ritualClasses = { "WayofTime.alchemicalWizardry.api.ritual.RitualRegistry",
            "WayofTime.alchemicalWizardry.api.rituals.RitualRegistry" };
        for (final String className : ritualClasses) {
            try {
                final Class<?> ritualClass = Class.forName(className);
                ModRecipeBridge.logReflect("BloodMagic-Ritual", "loaded: " + className);
                final List<Object> ritualObjs = new ArrayList<>();
                ModRecipeBridge.collectRitualObjectsFromClass(ritualClass, ritualObjs, "BloodMagic-Ritual");
                for (final Object r : ritualObjs) {
                    if (null == r) continue;
                    try {
                        registered += ModRecipeBridge.processRitualObject(r, "BloodMagic-Ritual");
                    } catch (final Exception e) {
                        ModRecipeBridge.logReflectError("BloodMagic-Ritual", "processRitualObject", e);
                    }
                }
                registered += ModRecipeBridge.extractRecipesFromClass(ritualClass, "BloodMagic-Ritual");
            } catch (final ClassNotFoundException e) {
                ModRecipeBridge.logReflect("BloodMagic-Ritual", "class not found: " + className);
            }
        }

        ModRecipeBridge.logModSummary("Blood Magic", registered);
        return registered;
    }

    // ==================== Botania (植物魔法) ====================

    /**
     * Scans Botania's recipe systems: Mana Infusion (魔力注入), Runic Altar (符文祭坛),
     * Petal Apothecary (花瓣炼药台), Elven Trade (精灵贸易).
     * <p>
     * Botania stores recipes as static Lists in BotaniaAPI:
     * - manaInfusionRecipes, petalRecipes, runeAltarRecipes, elvenTradeRecipes
     * Each recipe object has getOutput() and getInputs() methods.
     * <p>
     * 扫描植物魔法的配方系统：魔力注入、符文祭坛、花瓣炼药台、精灵贸易。
     * 植物魔法在 BotaniaAPI 中存储为静态 List。
     */
    private static int scanBotania() {
        final Class<?> apiClass = ModRecipeBridge
            .tryLoadClass("vazkii.botania.api.BotaniaAPI", "vazkii.botania.api.recipe.RecipeManaInfusion");
        if (null == apiClass) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Botania ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Botania " + tr("detected, scanning"));

        // BotaniaAPI has static List fields for each recipe type
        // BotaniaAPI 有每种配方类型的静态 List 字段
        registered += ModRecipeBridge.extractRecipesFromClass(apiClass, "Botania");

        // Also try specific recipe classes that may have their own static lists
        // 也尝试可能有自己静态列表的特定配方类
        final String[] recipeClasses = { "vazkii.botania.api.recipe.RecipeManaInfusion",
            "vazkii.botania.api.recipe.RecipeRuneAltar", "vazkii.botania.api.recipe.RecipePetals",
            "vazkii.botania.api.recipe.RecipeElvenTrade", "vazkii.botania.api.recipe.RecipePureDaisy",
            "vazkii.botania.api.recipe.RecipeBrew" };
        for (final String c : recipeClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "Botania");

        ModRecipeBridge.logModSummary("Botania", registered);
        return registered;
    }

    // ==================== Forestry (林业) ====================

    /**
     * Scans Forestry's recipe systems: Carpenter (木工机), Centrifuge (离心机),
     * Squeezer (榨汁机), Fermenter (发酵机), Still (蒸馏器), Moistener (湿润器).
     * <p>
     * Forestry uses RecipeManagers with static manager fields.
     * Each manager has getRecipes() returning a collection.
     * <p>
     * 扫描林业的配方系统：木工机、离心机、榨汁机、发酵机、蒸馏器、湿润器。
     * 林业使用 RecipeManagers 及其静态管理器字段。
     */
    private static int scanForestry() {
        final Class<?> detected = ModRecipeBridge.tryLoadClass(
            "forestry.api.recipes.RecipeManagers",
            "forestry.api.recipes.ICarpenterManager",
            "forestry.Forestry");
        if (null == detected) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Forestry ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Forestry " + tr("detected, scanning"));

        // RecipeManagers has static fields for each machine type
        // RecipeManagers 有每种机器类型的静态字段
        registered += ModRecipeBridge.extractRecipesFromClass(detected, "Forestry");

        final String[] managerClasses = { "forestry.api.recipes.RecipeManagers",
            "forestry.factory.recipes.CarpenterRecipeManager", "forestry.factory.recipes.CentrifugeRecipeManager",
            "forestry.factory.recipes.FabricatorRecipeManager", "forestry.factory.recipes.FermenterRecipeManager",
            "forestry.factory.recipes.MoistenerRecipeManager", "forestry.factory.recipes.SqueezerRecipeManager",
            "forestry.factory.recipes.StillRecipeManager" };
        for (final String c : managerClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "Forestry");

        ModRecipeBridge.logModSummary("Forestry", registered);
        return registered;
    }

    // ==================== Tinkers' Construct (匠魂) ====================

    /**
     * Scans Tinkers' Construct's recipe systems: Smeltery (冶炼炉),
     * Casting Table/Basin (浇铸台/盆).
     * <p>
     * TConstruct stores recipes in TConstructRegistry and specific recipe classes.
     * <p>
     * 扫描匠魂的配方系统：冶炼炉、浇铸台/盆。
     * 匠魂在 TConstructRegistry 和特定配方类中存储配方。
     */
    private static int scanTinkersConstruct() {
        final Class<?> detected = ModRecipeBridge
            .tryLoadClass("tconstruct.library.TConstructRegistry", "tconstruct.TConstruct");
        if (null == detected) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Tinkers' Construct ==========");
        ModFileLogger
            .info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Tinkers' Construct " + tr("detected, scanning"));

        registered += ModRecipeBridge.extractRecipesFromClass(detected, "TConstruct");

        final String[] recipeClasses = { "tconstruct.library.TConstructRegistry",
            "tconstruct.library.crafting.CastingRecipe", "tconstruct.library.crafting.AlloyMix",
            "tconstruct.library.crafting.DryingRackRecipes", "tconstruct.library.crafting.LiquidCasting",
            "tconstruct.smeltery.TinkersSmeltery" };
        for (final String c : recipeClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "TConstruct");

        ModRecipeBridge.logModSummary("Tinkers' Construct", registered);
        return registered;
    }

    // ==================== EnderIO (末影接口) ====================

    /**
     * Scans EnderIO's recipe systems: Alloy Smelter (合金冶炼炉),
     * SAG Mill (SAG磨粉机), Enchanter (附魔器).
     * <p>
     * EnderIO uses manager classes in crazypants.enderio.machine.* packages.
     * <p>
     * 扫描 EnderIO 的配方系统：合金冶炼炉、SAG磨粉机、附魔器。
     * EnderIO 在 crazypants.enderio.machine.* 包中使用管理器类。
     */
    private static int scanEnderIO() {
        final Class<?> detected = ModRecipeBridge
            .tryLoadClass("crazypants.enderio.EnderIO", "crazypants.enderio.api.EnderIOAPI");
        if (null == detected) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== EnderIO ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " EnderIO " + tr("detected, scanning"));

        final String[] recipeClasses = { "crazypants.enderio.machine.alloy.AlloyRecipeManager",
            "crazypants.enderio.machine.alloy.BasicAlloyRecipe",
            "crazypants.enderio.machine.sagmill.SagMillRecipeManager",
            "crazypants.enderio.machine.enchanter.EnchanterRecipeManager",
            "crazypants.enderio.machine.crusher.CrusherRecipeManager",
            "crazypants.enderio.machine.recipe.RecipeConfig" };
        for (final String c : recipeClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "EnderIO");

        ModRecipeBridge.logModSummary("EnderIO", registered);
        return registered;
    }

    // ==================== Railcraft (铁路) ====================

    /**
     * Scans Railcraft's recipe systems: Rolling Machine (轧制机),
     * Blast Furnace (高炉), Rock Crusher (碎石机), Coke Oven (焦炉).
     * <p>
     * Railcraft uses RailcraftCraftingManager and specific handler classes.
     * <p>
     * 扫描 Railcraft 的配方系统：轧制机、高炉、碎石机、焦炉。
     * Railcraft 使用 RailcraftCraftingManager 和特定处理器类。
     */
    private static int scanRailcraft() {
        final Class<?> detected = ModRecipeBridge
            .tryLoadClass("mods.railcraft.api.crafting.RailcraftCraftingManager", "mods.railcraft.common.Railcraft");
        if (null == detected) return 0;

        int registered = 0;
        ModFileLogger.scan(tr("[Mod recipes]") + " ========== Railcraft ==========");
        ModFileLogger.info("[ThaumicAllAspect] " + tr("[Mod recipes]") + " Railcraft " + tr("detected, scanning"));

        registered += ModRecipeBridge.extractRecipesFromClass(detected, "Railcraft");

        final String[] recipeClasses = { "mods.railcraft.api.crafting.RailcraftCraftingManager",
            "mods.railcraft.api.crafting.IBlastFurnaceCraftingManager",
            "mods.railcraft.api.crafting.ICokeOvenCraftingManager",
            "mods.railcraft.api.crafting.IRockCrusherCraftingManager",
            "mods.railcraft.common.util.crafting.RollingMachineCraftingManager",
            "mods.railcraft.common.util.crafting.BlastFurnaceCraftingManager",
            "mods.railcraft.common.util.crafting.CokeOvenCraftingManager",
            "mods.railcraft.common.util.crafting.RockCrusherCraftingManager" };
        for (final String c : recipeClasses) registered += ModRecipeBridge.tryExtractFromClass(c, "Railcraft");

        ModRecipeBridge.logModSummary("Railcraft", registered);
        return registered;
    }

    // ==================== Mod Summary Helper ====================

    /**
     * Logs a per-mod summary line if any items were registered.
     * 如果有物品被注册，记录每个模组的总结行。
     */
    private static void logModSummary(final String modName, final int registered) {
        if (0 < registered) {
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
     * <p>
     * 从类的静态字段中收集仪式对象。
     * 如果 List 字段的元素类名包含 "ritual"/"necro"/"rite"，或者元素具有仪式典型方法，
     * 则将其视为仪式列表。
     */
    private static void collectRitualObjectsFromClass(final Class<?> clazz, final List<Object> collector,
        final String context) {
        Class<?> c = clazz;
        while (null != c && Object.class != c) {
            for (final Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    final Object val = f.get(null);
                    if (val instanceof List) {
                        final List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            Object first = null;
                            for (final Object o : list) {
                                if (null != o) {
                                    first = o;
                                    break;
                                }
                            }
                            if (null != first) {
                                final String typeName = first.getClass()
                                    .getName()
                                    .toLowerCase();
                                if (typeName.contains("ritual") || typeName.contains("necro")
                                    || typeName.contains("rite")) {
                                    ModRecipeBridge.logReflect(
                                        context,
                                        "found ritual list in field " + f.getName()
                                            + " (type="
                                            + first.getClass()
                                                .getName()
                                            + ", size="
                                            + list.size()
                                            + ")");
                                    collector.addAll(list);
                                } else if (ModRecipeBridge.hasRitualMethods(first)) {
                                    ModRecipeBridge.logReflect(
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
                        final Map<?, ?> map = (Map<?, ?>) val;
                        for (final Object v : map.values()) {
                            if (null != v) {
                                final String typeName = v.getClass()
                                    .getName()
                                    .toLowerCase();
                                if (typeName.contains("ritual") || typeName.contains("necro")
                                    || typeName.contains("rite")) {
                                    ModRecipeBridge.logReflect(
                                        context,
                                        "found ritual map in field " + f.getName() + " (size=" + map.size() + ")");
                                    collector.addAll(map.values());
                                    break;
                                }
                            }
                        }
                    }
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError(context, "field " + f.getName() + " in " + clazz.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
    }

    /**
     * Checks if an object has methods typical of a ritual recipe.
     * Returns true if at least 2 of: getOfferings, getSacrifice, getOutput, getRecipeOutput exist.
     * <p>
     * 检查对象是否具有仪式配方的典型方法。
     * 如果 getOfferings, getSacrifice, getOutput, getRecipeOutput 中至少 2 个存在则返回 true。
     */
    private static boolean hasRitualMethods(final Object obj) {
        int found = 0;
        for (final String name : new String[] { "getOfferings", "getSacrifice", "getOutput", "getRecipeOutput" }) {
            try {
                obj.getClass()
                    .getMethod(name);
                found++;
            } catch (final NoSuchMethodException ignored) {}
        }
        return 2 <= found;
    }

    /**
     * Processes a single ritual object: extracts output + inputs (offerings + sacrifice).
     * <p>
     * Uses reflection to try multiple method names for output, offerings, and sacrifice.
     * All attempts (success and failure) are logged to the scan log.
     * <p>
     * 处理单个仪式对象：提取产物 + 输入材料（祭品 + 祭坛祭品）。
     * 使用反射尝试多个方法名。所有尝试（成功和失败）都记录到扫描日志。
     */
    private static int processRitualObject(final Object ritual, final String modLabel) {
        final String ritualType = ritual.getClass()
            .getName();
        ItemStack output = null;
        final List<ItemStack> allInputs = new ArrayList<>();

        // --- Extract output ---
        // NecronomiconCreationRitual uses getItem(), not getOutput()
        // NecronomiconCreationRitual 使用 getItem() 而非 getOutput()
        for (final String mName : new String[] { "getItem", "getOutput", "getRecipeOutput", "getResult" }) {
            try {
                final Method m = ritual.getClass()
                    .getMethod(mName);
                final Object result = m.invoke(ritual);
                if (result instanceof ItemStack) {
                    output = (ItemStack) result;
                    ModRecipeBridge.logReflect(modLabel, ritualType + "." + mName + "() -> ItemStack OK");
                    break;
                }
            } catch (final NoSuchMethodException e) {
                ModRecipeBridge.logReflect(modLabel, ritualType + "." + mName + "() not found");
            } catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, ritualType + "." + mName + "()", e);
            }
        }

        if (null == output) {
            output = ModRecipeBridge.findItemStackField(ritual, modLabel, "item", "output", "result");
        }
        if (null == output || null == output.getItem()) {
            ModRecipeBridge.logReflect(modLabel, ritualType + ": no output found, skipping");
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
        for (final String mName : new String[] { "getOfferings", "getInputs", "getIngredients", "getComponents" }) {
            try {
                final Method m = ritual.getClass()
                    .getMethod(mName);
                final Object result = m.invoke(ritual);
                if (result instanceof Object[]) {
                    for (final Object o : (Object[]) result) {
                        if (null == o) continue;
                        final ItemStack s = ModRecipeBridge.objectToItemStack(o);
                        if (null != s && null != s.getItem()) allInputs.add(s);
                    }
                    ModRecipeBridge
                        .logReflect(modLabel, ritualType + "." + mName + "() -> " + allInputs.size() + " offerings");
                    break;
                } else if (result instanceof List) {
                    for (final Object o : (List<?>) result) {
                        final ItemStack s = ModRecipeBridge.objectToItemStack(o);
                        if (null != s && null != s.getItem()) allInputs.add(s);
                    }
                    ModRecipeBridge
                        .logReflect(modLabel, ritualType + "." + mName + "() -> " + allInputs.size() + " offerings");
                    break;
                }
            } catch (final NoSuchMethodException e) {
                ModRecipeBridge.logReflect(modLabel, ritualType + "." + mName + "() not found");
            } catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, ritualType + "." + mName + "()", e);
            }
        }

        if (allInputs.isEmpty()) {
            ModRecipeBridge
                .collectItemStackArrayField(ritual, allInputs, modLabel, "offerings", "inputs", "ingredients");
        }

        // --- Extract sacrifice / central item ---
        // getSacrifice() returns Object (can be ItemStack, String/OreDict, or null)
        // getSacrifice() 返回 Object（可能是 ItemStack、String/矿辞名或 null）
        for (final String mName : new String[] { "getSacrifice", "getInput", "getCatalyst", "getCentralItem" }) {
            try {
                final Method m = ritual.getClass()
                    .getMethod(mName);
                final Object result = m.invoke(ritual);
                if (null != result) {
                    final ItemStack sacrifice = ModRecipeBridge.objectToItemStack(result);
                    if (null != sacrifice && null != sacrifice.getItem()) {
                        allInputs.add(sacrifice);
                        ModRecipeBridge.logReflect(modLabel, ritualType + "." + mName + "() -> sacrifice OK");
                    }
                    break;
                }
            } catch (final NoSuchMethodException e) {
                ModRecipeBridge.logReflect(modLabel, ritualType + "." + mName + "() not found");
            } catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, ritualType + "." + mName + "()", e);
            }
        }

        if (allInputs.isEmpty()) {
            final ItemStack sacrifice = ModRecipeBridge
                .findItemStackField(ritual, modLabel, "sacrifice", "input", "catalyst", "centralItem");
            if (null != sacrifice && null != sacrifice.getItem()) allInputs.add(sacrifice);
        }

        if (allInputs.isEmpty()) {
            ModRecipeBridge.logReflect(modLabel, ritualType + ": no inputs found, skipping");
            return 0;
        }

        return ModRecipeBridge.tryDeriveFromInputs(output, allInputs, modLabel);
    }

    // ==================== Field Reflection Helpers / 字段反射辅助 ====================

    /**
     * Finds the first non-null ItemStack field from an object whose name matches one of the hints.
     * 从对象中查找名称匹配提示的第一个非空 ItemStack 字段。
     */
    private static ItemStack findItemStackField(final Object obj, final String context, final String... nameHints) {
        Class<?> c = obj.getClass();
        while (null != c && Object.class != c) {
            for (final Field f : c.getDeclaredFields()) {
                try {
                    final String fn = f.getName()
                        .toLowerCase();
                    for (final String hint : nameHints) {
                        if (fn.contains(hint.toLowerCase())) {
                            f.setAccessible(true);
                            final Object val = f.get(obj);
                            if (val instanceof ItemStack) {
                                ModRecipeBridge.logReflect(context, "found ItemStack field: " + f.getName());
                                return (ItemStack) val;
                            }
                        }
                    }
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError(context, "field " + f.getName(), e);
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
    private static void collectItemStackArrayField(final Object obj, final List<ItemStack> collector,
        final String context, final String... nameHints) {
        Class<?> c = obj.getClass();
        while (null != c && Object.class != c) {
            for (final Field f : c.getDeclaredFields()) {
                try {
                    final String fn = f.getName()
                        .toLowerCase();
                    for (final String hint : nameHints) {
                        if (fn.contains(hint.toLowerCase())) {
                            f.setAccessible(true);
                            final Object val = f.get(obj);
                            if (val instanceof ItemStack[]) {
                                for (final ItemStack s : (ItemStack[]) val) {
                                    if (null != s && null != s.getItem()) collector.add(s);
                                }
                                ModRecipeBridge.logReflect(
                                    context,
                                    "found ItemStack[] field: " + f.getName() + " (" + collector.size() + " items)");
                                return;
                            } else if (val instanceof List) {
                                for (final Object o : (List<?>) val) {
                                    final ItemStack s = ModRecipeBridge.toItemStack(o);
                                    if (null != s && null != s.getItem()) collector.add(s);
                                }
                                ModRecipeBridge.logReflect(
                                    context,
                                    "found List field: " + f.getName() + " (" + collector.size() + " items)");
                                return;
                            }
                        }
                    }
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError(context, "field " + f.getName(), e);
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
     * <p>
     * 从一个类中通用地反射提取配方。
     * 先尝试 getInstance()/getRecipes() 方法，再扫描静态和实例字段。
     * 所有反射尝试都记录到扫描日志。
     */
    private static int extractRecipesFromClass(final Class<?> clazz, final String modLabel) {
        int registered = 0;
        Object instance = null;

        // Phase 1: Try to obtain a singleton instance via getInstance()/instance()
        // 阶段 1：尝试通过 getInstance()/instance() 获取单例实例
        for (final String mName : new String[] { "getInstance", "instance" }) {
            try {
                final Method m = clazz.getMethod(mName);
                if (Modifier.isStatic(m.getModifiers())) {
                    final Object result = m.invoke(null);
                    if (null != result) {
                        ModRecipeBridge.logReflect(
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
            } catch (final NoSuchMethodException ignored) {} catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, clazz.getSimpleName() + "." + mName + "()", e);
            }
        }

        // Phase 2: Try recipe-returning methods (static or on the instance)
        // 阶段 2：尝试返回配方的方法（静态方法或实例方法）
        for (final String mName : new String[] { "getRecipes", "getRecipeList", "getTransmutations",
            "getCrystallizations", "getSmeltingList", "getCraftings", "getAllRecipes" }) {
            try {
                final Method m = clazz.getMethod(mName);
                final boolean isStatic = Modifier.isStatic(m.getModifiers());
                final Object target = isStatic ? null : instance;
                if (!isStatic && null == target) continue;
                final Object result = m.invoke(target);
                if (result instanceof Map) {
                    final Map<?, ?> map = (Map<?, ?>) result;
                    ModRecipeBridge.logReflect(
                        modLabel,
                        clazz.getSimpleName() + "." + mName + "() -> Map (size=" + map.size() + ")");
                    registered += ModRecipeBridge.processRecipeMap(map, modLabel);
                } else if (result instanceof List) {
                    final List<?> list = (List<?>) result;
                    ModRecipeBridge.logReflect(
                        modLabel,
                        clazz.getSimpleName() + "." + mName + "() -> List (size=" + list.size() + ")");
                    registered += ModRecipeBridge.processRecipeList(list, modLabel);
                }
            } catch (final NoSuchMethodException ignored) {} catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, clazz.getSimpleName() + "." + mName + "()", e);
            }
        }

        // Phase 3: Scan instance fields (if we have an instance)
        // 阶段 3：扫描实例字段（如果有实例的话）
        if (null != instance) {
            registered += ModRecipeBridge.scanFieldsForRecipes(instance, instance.getClass(), modLabel);
        }

        // Phase 4: Scan static fields only
        // 阶段 4：仅扫描静态字段
        registered += ModRecipeBridge.scanStaticFields(clazz, modLabel);

        return registered;
    }

    /**
     * Scans instance fields of a class for Map/List recipe registries.
     * Only reads non-static fields using the given instance.
     * <p>
     * 扫描类的实例字段，查找 Map/List 配方注册表。
     * 仅使用给定实例读取非静态字段。
     */
    private static int scanFieldsForRecipes(final Object instance, final Class<?> clazz, final String modLabel) {
        if (null == instance) return 0;
        int registered = 0;
        Class<?> c = clazz;
        while (null != c && Object.class != c) {
            for (final Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    final Object val = f.get(instance);
                    if (val instanceof Map) {
                        final Map<?, ?> map = (Map<?, ?>) val;
                        if (!map.isEmpty()) {
                            ModRecipeBridge.logReflect(
                                modLabel,
                                "field " + c.getSimpleName() + "." + f.getName() + " -> Map (size=" + map.size() + ")");
                            registered += ModRecipeBridge.processRecipeMap(map, modLabel);
                        }
                    } else if (val instanceof List) {
                        final List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            ModRecipeBridge.logReflect(
                                modLabel,
                                "field " + c
                                    .getSimpleName() + "." + f.getName() + " -> List (size=" + list.size() + ")");
                            registered += ModRecipeBridge.processRecipeList(list, modLabel);
                        }
                    }
                } catch (final Exception e) {
                    ModRecipeBridge.logReflectError(
                        modLabel,
                        "field " + c.getSimpleName() + "." + f.getName() + " (instance=" + (null != instance) + ")",
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
     * <p>
     * 仅扫描类的静态字段，查找 Map/List 配方注册表。
     * 无需实例即可安全调用 — 对静态字段 f.get(null) 是合法的。
     */
    private static int scanStaticFields(final Class<?> clazz, final String modLabel) {
        int registered = 0;
        Class<?> c = clazz;
        while (null != c && Object.class != c) {
            for (final Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    final Object val = f.get(null);
                    if (null == val) continue;
                    if (val instanceof Map) {
                        final Map<?, ?> map = (Map<?, ?>) val;
                        if (!map.isEmpty()) {
                            ModRecipeBridge.logReflect(
                                modLabel,
                                "static field " + c
                                    .getSimpleName() + "." + f.getName() + " -> Map (size=" + map.size() + ")");
                            registered += ModRecipeBridge.processRecipeMap(map, modLabel);
                        }
                    } else if (val instanceof List) {
                        final List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            ModRecipeBridge.logReflect(
                                modLabel,
                                "static field " + c
                                    .getSimpleName() + "." + f.getName() + " -> List (size=" + list.size() + ")");
                            registered += ModRecipeBridge.processRecipeList(list, modLabel);
                        }
                    } else if (ModRecipeBridge.isManagerCandidate(val)) {
                        final String path = c.getSimpleName() + "." + f.getName();
                        registered += ModRecipeBridge.tryExtractFromManager(val, modLabel, path);
                    }
                } catch (final Exception e) {
                    ModRecipeBridge
                        .logReflectError(modLabel, "static field " + c.getSimpleName() + "." + f.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
        return registered;
    }

    /**
     * Checks whether an object looks like a recipe manager worth probing.
     * Excludes primitives, wrappers, strings, classes, and other non-manager types.
     * <p>
     * 判断对象是否像一个值得探测的配方管理器。
     * 排除基本类型、包装类型、字符串、Class 等非管理器类型。
     */
    private static boolean isManagerCandidate(final Object val) {
        return !(val instanceof Number) && !(val instanceof Boolean)
            && !(val instanceof String)
            && !(val instanceof Character)
            && !(val instanceof Class);
    }

    /**
     * Tries to call recipe-returning methods (getRecipes, getRecipeList, etc.)
     * on a manager-like object obtained from a static field.
     * Handles Forestry's pattern: RecipeManagers.carpenterManager.getRecipes()
     * <p>
     * 尝试在从静态字段获取的管理器对象上调用返回配方的方法。
     * 处理林业的模式：RecipeManagers.carpenterManager.getRecipes()
     */
    private static int tryExtractFromManager(final Object manager, final String modLabel, final String fieldPath) {
        int registered = 0;
        for (final String mName : new String[] { "getRecipes", "getRecipeList", "getAllRecipes", "recipes",
            "getSmeltingList", "getCraftings" }) {
            try {
                final Method m = manager.getClass()
                    .getMethod(mName);
                final Object result = m.invoke(manager);
                if (result instanceof Map) {
                    final Map<?, ?> map = (Map<?, ?>) result;
                    ModRecipeBridge
                        .logReflect(modLabel, fieldPath + "." + mName + "() -> Map (size=" + map.size() + ")");
                    registered += ModRecipeBridge.processRecipeMap(map, modLabel);
                } else if (result instanceof List) {
                    final List<?> list = (List<?>) result;
                    ModRecipeBridge
                        .logReflect(modLabel, fieldPath + "." + mName + "() -> List (size=" + list.size() + ")");
                    registered += ModRecipeBridge.processRecipeList(list, modLabel);
                } else if (result instanceof Collection) {
                    final List<?> list = new ArrayList<>((Collection<?>) result);
                    ModRecipeBridge
                        .logReflect(modLabel, fieldPath + "." + mName + "() -> Collection (size=" + list.size() + ")");
                    registered += ModRecipeBridge.processRecipeList(list, modLabel);
                }
            } catch (final NoSuchMethodException ignored) {} catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, fieldPath + "." + mName + "()", e);
            }
        }
        return registered;
    }

    // ==================== Recipe Processing / 配方处理 ====================

    /**
     * Processes a Map that may contain input→output recipe pairs.
     * 处理可能包含输入→输出配方对的 Map。
     */
    private static int processRecipeMap(final Map<?, ?> map, final String modLabel) {
        int registered = 0;
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            try {
                final ItemStack input = ModRecipeBridge.toItemStack(entry.getKey());
                final Object val = entry.getValue();

                if (val instanceof ItemStack) {
                    registered += ModRecipeBridge.tryDeriveFromInput((ItemStack) val, input, modLabel);
                } else if (val instanceof ItemStack[]) {
                    for (final ItemStack out : (ItemStack[]) val) {
                        registered += ModRecipeBridge.tryDeriveFromInput(out, input, modLabel);
                    }
                }
            } catch (final Exception e) {
                ModRecipeBridge.logReflectError(modLabel, "processRecipeMap entry", e);
            }
        }
        return registered;
    }

    /**
     * Processes a List that may contain recipe objects with input/output fields.
     * Uses reflection to extract input and output ItemStacks from each element.
     * Each method probe attempt is logged.
     * <p>
     * 处理可能包含配方对象的 List。
     * 使用反射从每个元素中提取输入和输出 ItemStack。
     * 每个方法探测尝试都会记录日志。
     */
    private static int processRecipeList(final List<?> list, final String modLabel) {
        int registered = 0;
        boolean loggedType = false;

        for (final Object recipe : list) {
            if (null == recipe) continue;

            // Log the type of the first non-null element for debugging
            // 记录第一个非空元素的类型以便调试
            if (!loggedType) {
                ModRecipeBridge.logReflect(
                    modLabel,
                    "processRecipeList: element type = " + recipe.getClass()
                        .getName());
                loggedType = true;
            }

            try {
                ItemStack output = null;
                ItemStack input = null;
                final List<ItemStack> inputs = new ArrayList<>();

                for (final String mName : new String[] { "getOutput", "getRecipeOutput", "getResult" }) {
                    try {
                        final Method m = recipe.getClass()
                            .getMethod(mName);
                        final Object result = m.invoke(recipe);
                        if (result instanceof ItemStack) {
                            output = (ItemStack) result;
                            break;
                        }
                    } catch (final NoSuchMethodException ignored) {
                        // Expected probe — not logged per-element to avoid spam
                    } catch (final Exception e) {
                        ModRecipeBridge.logReflectError(
                            modLabel,
                            recipe.getClass()
                                .getSimpleName() + "."
                                + mName
                                + "()",
                            e);
                    }
                }

                for (final String mName : new String[] { "getInput", "getRecipeInput", "getCatalyst", "getIngredient",
                    "getInputs", "getIngredients" }) {
                    try {
                        final Method m = recipe.getClass()
                            .getMethod(mName);
                        final Object result = m.invoke(recipe);
                        if (result instanceof ItemStack) {
                            input = (ItemStack) result;
                            break;
                        } else if (result instanceof ItemStack[]) {
                            for (final ItemStack s : (ItemStack[]) result) {
                                if (null != s) inputs.add(s);
                            }
                            break;
                        } else if (result instanceof List) {
                            for (final Object o : (List<?>) result) {
                                final ItemStack s = ModRecipeBridge.toItemStack(o);
                                if (null != s) inputs.add(s);
                            }
                            break;
                        }
                    } catch (final NoSuchMethodException ignored) {
                        // Expected probe
                    } catch (final Exception e) {
                        ModRecipeBridge.logReflectError(
                            modLabel,
                            recipe.getClass()
                                .getSimpleName() + "."
                                + mName
                                + "()",
                            e);
                    }
                }

                if (null != output && null != output.getItem()) {
                    if (null != input) {
                        registered += ModRecipeBridge.tryDeriveFromInput(output, input, modLabel);
                    } else if (!inputs.isEmpty()) {
                        registered += ModRecipeBridge.tryDeriveFromInputs(output, inputs, modLabel);
                    } else {
                        // Output found but standard input probes failed.
                        // Try ritual-specific processing (handles getOfferings/getSacrifice).
                        // 找到产物但标准输入探测全部失败。
                        // 尝试仪式专用处理（处理 getOfferings/getSacrifice）。
                        registered += ModRecipeBridge.processRitualObject(recipe, modLabel);
                    }
                } else {
                    // Standard output probes failed — try ritual processing as fallback
                    // 标准产物探测失败 — 尝试仪式处理作为回退
                    final String cn = recipe.getClass()
                        .getName()
                        .toLowerCase();
                    if (cn.contains("ritual") || cn.contains("necro")
                        || cn.contains("rite")
                        || ModRecipeBridge.hasRitualMethods(recipe)) {
                        registered += ModRecipeBridge.processRitualObject(recipe, modLabel);
                    }
                }
            } catch (final Exception e) {
                ModRecipeBridge.logReflectError(
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
    private static int tryDeriveFromInput(final ItemStack output, final ItemStack input, final String modLabel) {
        if (null == output || null == output.getItem()) return 0;
        if (null == input || null == input.getItem()) return 0;
        if (AspectUtils.hasAspect(output)) return 0;

        final AspectList inputAsp = AspectDeriver.getOrGenerateAspectsFor(input, 0, new HashSet<>());
        if (null == inputAsp || 0 == inputAsp.size()) return 0;

        final AspectList result = AspectUtils.scaleAspects(inputAsp, AspectUtils.RECIPE_DECAY);
        if (null == result || 0 == result.size()) return 0;

        ThaumcraftApi.registerObjectTag(
            output,
            AspectUtils.ensureMinOnePerAspect(result)
                .copy());
        AspectUtils.CACHE.put(AspectUtils.key(output), result.copy());
        AspectUtils.statNewlyRegistered++;

        final String id = AspectUtils.key(output);
        String displayName;
        try {
            displayName = output.getDisplayName();
        } catch (final Exception e) {
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
        AspectUtils.FAILED_IDS.remove(id.contains("@") ? id.substring(0, id.indexOf('@')) : id);
        return 1;
    }

    /**
     * Derive aspects from multiple inputs (RECIPE_DECAY: 90% decay, min 1), then register or merge with existing.
     * If the output has no aspects, register the derived list. If it has aspects, merge by
     * taking the maximum of each aspect (existing vs derived) so we never drop existing and
     * can improve weak ones.
     * <p>
     * 从多个输入推导要素（50% 衰减），然后注册或与现有合并。
     * 若输出无要素则直接注册；若有则按每种要素取 max(现有, 推导) 合并，不覆盖且可增强弱要素。
     */
    private static int tryDeriveFromInputs(final ItemStack output, final List<ItemStack> inputs,
        final String modLabel) {
        if (null == output || null == output.getItem()) return 0;

        final AspectList combined = new AspectList();
        boolean hasAny = false;
        for (final ItemStack input : inputs) {
            if (null == input || null == input.getItem()) continue;
            final AspectList asp = AspectDeriver.getOrGenerateAspectsFor(input, 0, new HashSet<>());
            if (AspectUtils.hasPositiveAspectAmount(asp)) {
                combined.add(asp);
                hasAny = true;
            }
        }
        if (!hasAny) return 0;

        final AspectList derived = AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY);
        if (null == derived || 0 == derived.size()) return 0;

        AspectList result = derived.copy();
        if (AspectUtils.hasAspect(output)) {
            final AspectList existing = ThaumcraftApiHelper.getObjectAspects(output);
            if (AspectUtils.hasPositiveAspectAmount(existing)) {
                result = AspectUtils.mergeAspectsMax(existing, derived);
            }
        }

        if (null == result || 0 == result.size()) return 0;

        ThaumcraftApi.registerObjectTag(
            output,
            AspectUtils.ensureMinOnePerAspect(result)
                .copy());
        AspectUtils.CACHE.put(AspectUtils.key(output), result.copy());
        AspectUtils.statNewlyRegistered++;

        final String id = AspectUtils.key(output);
        String displayName;
        try {
            displayName = output.getDisplayName();
        } catch (final Exception e) {
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
        AspectUtils.FAILED_IDS.remove(id.contains("@") ? id.substring(0, id.indexOf('@')) : id);
        return 1;
    }

    /**
     * Safely converts an arbitrary object to an ItemStack if possible.
     * 安全地将任意对象转换为 ItemStack（如果可能的话）。
     */
    private static ItemStack toItemStack(final Object obj) {
        if (obj instanceof ItemStack) return (ItemStack) obj;
        return null;
    }

    /**
     * Converts Object to ItemStack. Handles ItemStack directly, and String as OreDict name
     * (returns the first registered ore for the name). Other types are ignored.
     * <p>
     * 将 Object 转为 ItemStack。直接处理 ItemStack；String 视为矿辞名
     * （返回该矿辞的第一个注册物品）。其他类型忽略。
     */
    private static ItemStack objectToItemStack(final Object obj) {
        if (null == obj) return null;
        if (obj instanceof ItemStack) return (ItemStack) obj;
        if (obj instanceof String) {
            final String oreName = (String) obj;
            try {
                final ArrayList<ItemStack> ores = OreDictionary.getOres(oreName);
                if (null != ores && !ores.isEmpty()) {
                    return ores.get(0);
                }
            } catch (final Exception e) {
                // OreDict lookup failed, ignore
            }
            return null;
        }
        return null;
    }
}
