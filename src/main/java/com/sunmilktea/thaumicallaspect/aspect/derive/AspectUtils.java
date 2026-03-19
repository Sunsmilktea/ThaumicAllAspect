package com.sunmilktea.thaumicallaspect.aspect.derive;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;
import com.sunmilktea.thaumicallaspect.logging.ModI18n;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.ThaumcraftApiHelper;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

/**
 * Shared state (caches, indexes) and utility methods used across the aspect scanning system.
 * 要素扫描系统中使用的共享状态（缓存、索引）和工具方法。
 */
public class AspectUtils {

    /**
     * Maximum recursion depth for recipe-based aspect derivation.
     * Prevents infinite loops when recipes reference each other in nested chains
     * (e.g., item A crafts from B, B crafts from C, ... back to A).
     * Value is loaded from config at startup (default 10).
     * 配方推导的最大递归深度，从配置文件加载（默认 10），用于防止嵌套配方链中的无限循环。
     */
    public static int MAX_RECIPE_DEPTH = 10;
    /**
     * Per-scan aspect cache keyed by "registryName@meta".
     * Cleared between multi-pass retries so that later passes can re-derive
     * items that previously had no aspects (because their ingredients weren't scanned yet).
     * 按 "注册名@meta" 为键的扫描期缓存。
     * 在多轮重试之间清空，以便后续轮次重新推导先前因原料未扫描而无要素的物品。
     */
    public static final Map<String, AspectList> CACHE = new HashMap<>();
    /**
     * Sorted set of item IDs (registryName@meta) that failed all derivation attempts.
     * Written to the failure log file at the end of scanning for manual review.
     * Uses TreeSet for deterministic, alphabetically sorted output.
     * 所有推导尝试均失败的物品 ID（注册名@meta）的有序集合。
     * 扫描结束后写入失败日志文件，供人工审查。使用 TreeSet 保证输出按字母排序且确定性。
     */
    public static final Set<String> FAILED_IDS = new TreeSet<>();
    /**
     * Local cache of Aspect objects by tag string, populated at class load
     * from all known Thaumcraft aspect tags. Avoids repeated Aspect.getAspect()
     * lookups and provides null-safety (missing aspects logged once at startup).
     * 按标签字符串缓存的 Aspect 对象本地映射，类加载时从所有已知 TC 要素标签填充。
     * 避免重复调用 Aspect.getAspect()，并提供空安全（缺失的要素在启动时记录一次警告）。
     */
    private static final Map<String, Aspect> ASPECT_MAP = new HashMap<>();

    // --- Pre-built indexes: built once at scan start for O(1) lookup by output Item ---
    // --- 预构建索引：扫描开始时一次性构建，按输出物品 O(1) 查找 ---
    /**
     * Collects all metadata values that should be scanned for a given Item.
     * Uses four complementary sources to ensure no valid sub-item is missed,
     * especially important for GregTech/JAOPCA items with many meta variants.
     * Always includes meta 0 as a baseline.
     * <p>
     * 收集给定 Item 需要扫描的所有 meta 值。
     * 使用四个互补来源确保不遗漏任何有效子物品，
     * 对于 GregTech/JAOPCA 等拥有大量 meta 变体的物品尤为重要。
     * 始终包含 meta 0 作为基线。
     *
     * @param item the Item to discover metas for / 要发现 meta 值的 Item
     * @return ordered set of metadata values to scan / 要扫描的 meta 值有序集合
     */
    private static final Class<?>[] GET_SUB_ITEMS_PARAMS = new Class<?>[] { Item.class, CreativeTabs.class,
        List.class };
    /**
     * Decay factor applied to recipe-derived aspects: each derivation layer
     * multiplies aspect amounts by this factor (e.g. 0.1 = 90% decay, keep 10%), with a floor of 1 per aspect.
     * Default is 0.1 but can be overridden from the config file at startup.
     * 配方推导的衰减因子：每层推导将要素数量乘以该系数（例如 0.1 即衰减 90%、保留 10%），每种要素至少 1 点。
     * 默认值为 0.1，可在配置文件中覆盖。
     */
    public static float RECIPE_DECAY = 0.1f;
    /**
     * Vanilla/Forge crafting recipes indexed by output Item. / 按输出物品索引的原版/Forge 合成配方。
     */
    public static Map<Item, List<IRecipe>> RECIPE_INDEX;
    /**
     * Thaumcraft-specific recipes (Crucible, Infusion, Arcane) indexed by output Item. / 按输出物品索引的神秘时代特有配方（坩埚、注魔、奥术）。
     */
    public static Map<Item, List<Object>> TC_RECIPE_INDEX;
    /**
     * Furnace smelting recipes indexed by output Item (input→output pairs). / 按输出物品索引的熔炉配方（输入→输出键值对）。
     */
    public static Map<Item, List<Map.Entry<ItemStack, ItemStack>>> FURNACE_INDEX;

    // --- Scan statistics: accumulated across all items in a single scan run ---
    // --- 扫描统计：在单次扫描中跨所有物品累加 ---
    /**
     * Items that received new aspects during this scan. / 本次扫描中获得新要素的物品数。
     */
    public static int statNewlyRegistered;
    /**
     * OreDictionary-registered metas per Item. / 每个 Item 在矿物词典中注册过的 meta 值集合。
     */
    public static Map<Item, Set<Integer>> ORE_DICT_METAS;
    /**
     * Metas that appear as crafting/furnace recipe outputs, per Item. / 每个 Item 作为合成/熔炉配方输出时出现的 meta 值集合。
     */
    public static Map<Item, Set<Integer>> RECIPE_OUTPUT_METAS;
    /**
     * Items that already had TC-registered aspects before this scan. / 本次扫描前已在 TC 中注册过要素的物品数。
     */
    public static int statAlreadyHad;
    /**
     * Items for which no aspects could be derived by any strategy. / 所有策略均无法推导出要素的物品数。
     */
    public static int statNoAspect;
    /**
     * Tracks which derivation strategy succeeded for the most recent depth-0 call.
     * Used for log output so the operator can see HOW each item got its aspects
     * (e.g., "recipe", "furnace", "oreDict", "fallback").
     * 记录最近一次 depth-0 调用中成功的推导策略。
     * 用于日志输出，让操作者了解每个物品的要素是如何获得的（如 "recipe"、"furnace"、"oreDict"、"fallback"）。
     */
    public static String lastDerivePath = "";

    /*
     * Pre-registers all known Thaumcraft aspect tags into ASPECT_MAP for fast,
     * null-safe lookups throughout the scanning process. This includes:
     * - The 6 primal aspects (aer, ignis, aqua, terra, ordo, perditio) via constants
     * - All vanilla Thaumcraft compound aspects
     * - GTNH addon aspects (Thaumic Bases, Witching Gadgets, etc.)
     * - LOTR mod faction/race aspects (edhel, nauglin, orchoth, shire, gondor, mordor...)
     * - Other modded aspects (radiation, magnetic, tofu, sakura, etc.)
     * If an aspect's parent mod isn't loaded, Aspect.getAspect() returns null and
     * putAspect() logs a warning — the tag is silently skipped with no crash.
     * 将所有已知的神秘时代要素标签预注册到 ASPECT_MAP，以便在扫描过程中快速、空安全地查找。包括：
     * - 6 种源质要素（aer、ignis、aqua、terra、ordo、perditio），通过常量引用
     * - 所有原版 TC 复合要素
     * - GTNH 附属要素（Thaumic Bases、Witching Gadgets 等）
     * - LOTR 模组的种族/阵营要素（edhel、nauglin、orchoth、shire、gondor、mordor……）
     * - 其他模组要素（radiation、magnetic、tofu、sakura 等）
     * 如果某要素的前置模组未加载，Aspect.getAspect() 返回 null，
     * putAspect() 会记录警告——该标签被静默跳过，不会崩溃。
     */
    static {
        AspectUtils.putAspect("aer", Aspect.AIR);
        AspectUtils.putAspect("ignis", Aspect.FIRE);
        AspectUtils.putAspect("aqua", Aspect.WATER);
        AspectUtils.putAspect("terra", Aspect.EARTH);
        AspectUtils.putAspect("ordo", Aspect.ORDER);
        AspectUtils.putAspect("perditio", Aspect.ENTROPY);

        final String[] tags = { "vacuos", "lux", "tempestas", "motus", "gelum", "vitreus", "victus", "venenum",
            "potentia", "permutatio", "metallum", "mortuus", "volatus", "tenebrae", "spiritus", "sano", "iter",
            "alienis", "praecantatio", "auram", "vitium", "limus", "herba", "arbor", "bestia", "corpus", "exanimis",
            "cognitio", "sensus", "humanus", "messis", "perfodio", "instrumentum", "meto", "telum", "tutamen", "fames",
            "lucrum", "fabrico", "pannus", "machina", "vinculum", "strontio", "nebrisum", "electrum", "magneto",
            "radio", "alkimia", "luxuria", "infernus", "superbia", "gula", "invidia", "desidia", "ira", "terminus",
            "tempus", "humilitas", "dimensio", "sanguino", "gravitas", "energy", "day", "night", "mattery", "alfirin",
            "abonnen", "edhel", "nauglin", "orchoth", "perian", "mornogol", "torog", "onodrim", "uhorm", "draugol",
            "valaraukar", "ithryn", "shire", "dunedain", "eredluin", "lindon", "gundabad", "angmar", "druardh",
            "dolguldur", "dale", "angdol", "lothlorien", "dunland", "isengard", "fangorn", "rohan", "gondor", "mordor",
            "dorwinion", "rhudel", "harad", "morwaith", "taurethrim", "pertorog", "utumno", "nazgul", "bree", "caelum",
            "tabernus", "contritio", "tyranny", "rubus", "excubitor", "coralos", "dreadia", "perplexus", "signum",
            "principia", "mru", "radiation", "matrix", "odachi", "proud", "dragon", "substance", "destroy", "universe",
            "space", "mana", "relic", "treasure", "evil", "dackmagic", "dream", "time", "rock", "paper", "lava",
            "magnetic", "electricity", "enchant", "alloy", "gravity", "vegetation", "cave", "darkenergy", "antimatter",
            "accessories", "element", "empire", "fossil", "history", "laputa", "tofu", "manaplate", "apsu", "crashrio",
            "formula", "grimoire", "mummu", "ouroborus", "prana", "prima", "tifinagh", "veo", "sombre", "providence",
            "elohim", "marix", "nigget", "hastur", "rune", "satanx", "sakura", "saxum", "granum" };
        for (final String tag : tags) {
            AspectUtils.putAspect(tag, Aspect.getAspect(tag));
        }
    }

    /**
     * Registers an aspect into the local ASPECT_MAP cache.
     * If the aspect is null (meaning the mod providing it is not loaded),
     * a warning is logged instead of storing null — this prevents NPEs
     * later during scanning without crashing on missing optional mods.
     * <p>
     * 将一个要素注册到本地 ASPECT_MAP 缓存中。
     * 如果要素为 null（即提供该要素的模组未加载），则记录警告而非存储 null——
     * 这样可以在扫描时避免 NPE，同时不会因缺少可选模组而崩溃。
     *
     * @param tag    the aspect's string identifier / 要素的字符串标识符
     * @param aspect the Aspect instance, or null if mod not loaded / Aspect 实例，模组未加载时为 null
     */
    private static void putAspect(final String tag, final Aspect aspect) {
        if (null != aspect) {
            AspectUtils.ASPECT_MAP.put(tag, aspect);
        } else {
            ModFileLogger.warn("[ThaumicAllAspect] " + ModI18n.tr("Aspect not found:") + " " + tag);
        }
    }

    /**
     * Looks up an aspect by tag, with live fallback to Aspect.getAspect(). / 按标签查找要素，回退到 Aspect.getAspect()。
     */
    public static Aspect getAspect(final String tag) {
        Aspect a = AspectUtils.ASPECT_MAP.get(tag);
        if (null == a) {
            a = Aspect.getAspect(tag);
            if (null != a) AspectUtils.ASPECT_MAP.put(tag, a);
        }
        return a;
    }

    /**
     * Builds the cache key for an ItemStack in the format "registryName@meta".
     * Used as HashMap key throughout the scanning system (CACHE, FAILED_IDS, etc.).
     * Falls back to "unknown" if the item isn't registered (should not happen in
     * normal gameplay, but guards against broken mod items).
     * <p>
     * 为 ItemStack 构建格式为 "注册名@meta" 的缓存键。
     * 在整个扫描系统中用作 HashMap 键（CACHE、FAILED_IDS 等）。
     * 若物品未注册则回退为 "unknown"（正常游戏中不应出现，但可防御损坏的模组物品）。
     *
     * @param stack the ItemStack to generate a key for / 要生成键的 ItemStack
     * @return a string in "registryName@meta" format / "注册名@meta" 格式的字符串
     */
    public static String key(final ItemStack stack) {
        final Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        return (null != name ? name.toString() : "unknown") + "@" + stack.getItemDamage();
    }

    /**
     * Checks whether TC already has non-empty aspects registered for this stack. / 检查TC是否已为此物品注册非空要素。
     */
    public static boolean hasAspect(final ItemStack stack) {
        if (!ThaumcraftApi.exists(stack.getItem(), stack.getItemDamage())) return false;
        final AspectList al = ThaumcraftApiHelper.getObjectAspects(stack);
        return AspectUtils.hasPositiveAspectAmount(al);
    }

    /**
     * Returns aspects for the stack only from CACHE or ThaumcraftApi — no derivation/recursion.
     * Used by the recipe-first pipeline so we do not recurse into getOrGenerateAspectsFor during rounds.
     * 仅从 CACHE 或 ThaumcraftApi 返回该物品的要素，不进行推导/递归。供配方优先管线在轮次中只查已有数据使用。
     */
    public static AspectList getExistingAspectsOnly(final ItemStack stack) {
        if (null == stack || null == stack.getItem()) return null;
        final ItemStack normalized = AspectUtils.normalizeForLookup(stack);
        final String k = AspectUtils.key(normalized);
        if (AspectUtils.CACHE.containsKey(k)) {
            final AspectList c = AspectUtils.CACHE.get(k);
            if (AspectUtils.hasPositiveAspectAmount(c)) return AspectUtils.ensureMinOnePerAspect(c)
                .copy();
        }
        if (ThaumcraftApi.exists(normalized.getItem(), normalized.getItemDamage())) {
            final AspectList al = ThaumcraftApiHelper.getObjectAspects(normalized);
            if (AspectUtils.hasPositiveAspectAmount(al)) return AspectUtils.ensureMinOnePerAspect(al)
                .copy();
        }
        return null;
    }

    /**
     * Returns true only when AspectList has a strictly positive total amount.
     * This treats lists like "metallum=0, potentia=0" as effectively empty.
     */
    public static boolean hasPositiveAspectAmount(final AspectList al) {
        return null != al && 0 < al.size() && 0 < AspectUtils.getAspectTotal(al);
    }

    /**
     * Normalizes an ItemStack for aspect lookup: same Item+meta are treated the same (NBT ignored).
     * 将 ItemStack 归一化用于要素查找：相同 Item+meta 视为同一物品（忽略 NBT）。
     */
    public static ItemStack normalizeForLookup(final ItemStack stack) {
        if (null == stack || null == stack.getItem()) return stack;
        int meta = stack.getItemDamage();
        if (OreDictionary.WILDCARD_VALUE == meta) meta = 0;
        return new ItemStack(stack.getItem(), 1, meta);
    }

    /**
     * From a list of slot alternatives, returns the best existing aspect list (by total) without derivation.
     * 从一槽备选物品中返回总分最高的已有要素列表，不进行推导。
     */
    public static AspectList getBestFromSlotExistingOnly(final List<ItemStack> options) {
        if (null == options || options.isEmpty()) return null;
        AspectList best = null;
        int bestScore = -1;
        for (final ItemStack opt : options) {
            if (null == opt) continue;
            final AspectList cur = AspectUtils.getExistingAspectsOnly(opt);
            if (null == cur || 0 == cur.size()) continue;
            final int score = AspectUtils.getAspectTotal(cur);
            if (score > bestScore) {
                bestScore = score;
                best = cur;
            }
        }
        return best;
    }

    /**
     * Scales all aspects in the list by the given factor, with a floor of 1 per aspect.
     * Typically called with {@link #RECIPE_DECAY} (0.1 = 90% decay) to reduce aspect inflation.
     * 按给定系数缩放所有要素，每种要素至少保留 1。通常与 RECIPE_DECAY（0.1，即 90% 衰减）一起使用。
     *
     * @param src    the source aspect list to scale / 要缩放的源要素列表
     * @param factor the multiplicative factor (e.g., 0.1 for 90% decay) / 乘法因子（如 0.1 表示衰减 90%）
     * @return a new scaled AspectList, or null if the result would be empty / 缩放后的新 AspectList，若结果为空则返回 null
     */
    public static AspectList scaleAspects(final AspectList src, final float factor) {
        if (null == src || 0 == src.size()) return null;
        final Aspect[] aspects = src.getAspects();
        if (null == aspects || 0 == aspects.length) return null;
        final AspectList res = new AspectList();
        for (final Aspect a : aspects) {
            if (null == a) continue;
            final int amt = src.getAmount(a);
            if (0 >= amt) continue;
            final int scaled = Math.max(1, Math.round(amt * factor));
            res.add(a, scaled);
        }
        return 0 < res.size() ? res : null;
    }

    /**
     * Normalizes an AspectList so that every aspect has at least amount 1.
     * Used as a final safety net before registering aspects to TC or writing to CACHE,
     * to avoid any accidental 0/negative amounts slipping through from configs or mods.
     * <p>
     * 将 AspectList 归一化为每种要素至少 1 点。
     * 在注册到 TC 或写入 CACHE 前作为最后一道安全网，避免配置或其它模组传入 0/负数数量。
     */
    public static AspectList ensureMinOnePerAspect(final AspectList src) {
        if (null == src || 0 == src.size()) return src;
        try {
            final Aspect[] aspects = src.getAspects();
            if (null == aspects || 0 == aspects.length) return src;
            final AspectList out = new AspectList();
            for (final Aspect a : aspects) {
                if (null == a) continue;
                final int amt = src.getAmount(a);
                if (amt <= 0) {
                    out.add(a, 1);
                } else {
                    out.add(a, amt);
                }
            }
            return 0 < out.size() ? out : src;
        } catch (final Exception e) {
            return src;
        }
    }

    /**
     * Returns the sum of all aspect amounts in the list.
     * Used as a scoring metric when choosing the best ingredient from alternatives.
     * 返回列表中所有要素数量的总和。用作从备选原料中选择最佳原料时的评分指标。
     *
     * @param al the aspect list to sum / 要求和的要素列表
     * @return total aspect score, or 0 if null / 要素总分，为 null 时返回 0
     */
    public static int getAspectTotal(final AspectList al) {
        if (null == al) return 0;
        final Aspect[] aspects = al.getAspects();
        if (null == aspects || 0 == aspects.length) return 0;
        int total = 0;
        for (final Aspect a : aspects) {
            if (null == a) continue;
            total += al.getAmount(a);
        }
        return total;
    }

    /**
     * Merges two aspect lists by taking the maximum amount for each aspect.
     * For every aspect present in either list, result has amount = max(a.getAmount(aspect), b.getAmount(aspect)).
     * Used to combine existing aspects with derived ones without dropping existing values.
     * <p>
     * 合并两个要素列表：每种要素取数量较大者。
     * 用于在已有要素基础上合并推导要素，不削弱已有值。
     */
    public static AspectList mergeAspectsMax(final AspectList a, final AspectList b) {
        if (null == a && null == b) return null;
        if (null == a) return b.copy();
        if (null == b) return a.copy();
        final AspectList out = new AspectList();
        final java.util.Set<Aspect> seen = new java.util.HashSet<>();
        final Aspect[] aa = a.getAspects();
        if (null != aa) {
            for (final Aspect asp : aa) {
                if (null == asp) continue;
                seen.add(asp);
                final int amtA = a.getAmount(asp);
                final int amtB = b.getAmount(asp);
                out.add(asp, Math.max(amtA, amtB));
            }
        }
        final Aspect[] ab = b.getAspects();
        if (null != ab) {
            for (final Aspect asp : ab) {
                if (null == asp || seen.contains(asp)) continue;
                final int amtA = a.getAmount(asp);
                final int amtB = b.getAmount(asp);
                out.add(asp, Math.max(amtA, amtB));
            }
        }
        return hasPositiveAspectAmount(out) ? out : null;
    }

    /**
     * Picks the ingredient with the highest total aspect score from a list of alternatives.
     * When a recipe slot accepts multiple OreDict items (e.g., any "ingotCopper"),
     * we want the richest-aspect variant so the derived output gets meaningful aspects.
     * Each candidate is recursively resolved via {@code AspectDeriver.getOrGenerateAspectsFor}.
     * <p>
     * 从备选原料列表中选出要素总分最高的一个。
     * 当配方槽接受多种矿辞物品时（如任意 "ingotCopper"），
     * 我们选择要素最丰富的变体，以便推导出的产物获得有意义的要素。
     * 每个候选项通过 {@code AspectDeriver.getOrGenerateAspectsFor} 递归解析。
     *
     * @param options  list of candidate ItemStacks for one recipe slot / 一个配方槽的候选 ItemStack 列表
     * @param depth    current recursion depth / 当前递归深度
     * @param visiting set of keys currently being visited (cycle detection) / 正在访问的键集合（环检测）
     * @return the aspect list of the best candidate, or null if none have aspects / 最佳候选的要素列表，若均无要素则返回 null
     */
    public static AspectList getBestFromSlot(final List<ItemStack> options, final int depth,
        final Set<String> visiting) {
        AspectList best = null;
        int bestScore = -1;

        for (final ItemStack opt : options) {
            if (null == opt) continue;
            final AspectList cur = AspectDeriver.getOrGenerateAspectsFor(opt, depth, visiting);
            if (!hasPositiveAspectAmount(cur)) continue;

            final int score = AspectUtils.getAspectTotal(cur);
            if (score > bestScore) {
                bestScore = score;
                best = cur;
            }
        }
        return best;
    }

    /**
     * Checks if two ItemStacks refer to the same item, with wildcard metadata support.
     * {@code OreDictionary.WILDCARD_VALUE} on either side means "any meta matches",
     * which is commonly used by Forge OreDict entries and recipe definitions
     * to represent "any damage value of this item".
     * <p>
     * 检查两个 ItemStack 是否指向同一物品，支持通配符 meta。
     * 任一方的 {@code OreDictionary.WILDCARD_VALUE} 表示"任意 meta 匹配"，
     * Forge 矿辞条目和配方定义中常用此值表示"该物品的任意损坏值"。
     *
     * @param a first ItemStack / 第一个 ItemStack
     * @param b second ItemStack / 第二个 ItemStack
     * @return true if they represent the same item (considering wildcards) / 若表示同一物品则返回 true（考虑通配符）
     */
    public static boolean sameItem(final ItemStack a, final ItemStack b) {
        if (null == a || null == b) return false;
        return a.getItem() == b.getItem()
            && (a.getItemDamage() == b.getItemDamage() || OreDictionary.WILDCARD_VALUE == a.getItemDamage()
                || OreDictionary.WILDCARD_VALUE == b.getItemDamage());
    }

    /**
     * Invokes Item.getSubItems(Item, CreativeTabs, List) via reflection so that Mixin/ASM or
     * different mapping (SRG/MCP) does not cause NoSuchMethodError. Tries MCP name then SRG name,
     * and walks class hierarchy. Safe no-op if method missing or invocation fails.
     * 通过反射调用，避免 NoSuchMethodError。先试 MCP 名再试 SRG 名，并沿类层次查找；找不到或调用失败时静默跳过。
     */
    private static void invokeGetSubItems(final Item item, final List<ItemStack> outList) {
        final Method m = AspectUtils.findGetSubItemsMethod(item.getClass());
        if (null == m) return;
        try {
            m.invoke(item, item, (CreativeTabs) null, outList);
        } catch (final Exception ignored) {}
    }

    private static Method findGetSubItemsMethod(final Class<?> start) {
        try {
            for (final String name : new String[] { "getSubItems", "func_150895_a" }) {
                for (Class<?> c = start; null != c && Item.class.isAssignableFrom(c); c = c.getSuperclass()) {
                    try {
                        final Method m = c.getDeclaredMethod(name, AspectUtils.GET_SUB_ITEMS_PARAMS);
                        m.setAccessible(true);
                        return m;
                    } catch (final NoSuchMethodException ignored) {}
                }
            }
        } catch (final NoClassDefFoundError e) {
            // 类层次遍历时触发依赖类加载失败则放弃，避免异常外抛
            return null;
        }
        return null;
    }

    /**
     * Detects if running on client side via FML.
     */
    public static boolean isClientSide() {
        return Side.CLIENT == FMLCommonHandler.instance()
            .getEffectiveSide();
    }

    public static Set<Integer> getMetasToScan(final Item item) {
        final Set<Integer> metas = new LinkedHashSet<>();
        metas.add(0);

        // Source 1: getSubItems — only on CLIENT side.
        // Some modded items implement getSubItems with client-only code (IIconRegister, etc.).
        // Calling this on dedicated server causes NoClassDefFoundError/ClassNotFoundException.
        // 来源 1：getSubItems —— 仅在客户端执行。
        // 部分模组的 getSubItems 实现包含客户端代码（IIconRegister 等），在服务器调用会崩溃。
        if (AspectUtils.isClientSide()) {
            try {
                final List<ItemStack> subItems = new ArrayList<>();
                AspectUtils.invokeGetSubItems(item, subItems);
                for (final ItemStack sub : subItems) {
                    if (null != sub && sub.getItem() == item) {
                        final int m = sub.getItemDamage();
                        if (0 <= m && OreDictionary.WILDCARD_VALUE != m) {
                            metas.add(m);
                        }
                    }
                }
            } catch (final Exception ignored) {}
        }

        // Source 2: OreDictionary registered metas — metas that appear in OreDict registrations.
        // Important for GT/JAOPCA compatibility: these mods register many meta variants
        // in the OreDict that may not appear in getSubItems.
        // 来源 2：矿辞注册的 meta —— 在矿物词典注册中出现的 meta 值。
        // 对 GT/JAOPCA 兼容性很重要：这些模组在矿辞中注册了许多可能不出现在 getSubItems 中的 meta 变体。
        if (null != ORE_DICT_METAS) {
            final Set<Integer> oreMetas = AspectUtils.ORE_DICT_METAS.get(item);
            if (null != oreMetas) metas.addAll(oreMetas);
        }

        // Source 3: Crafting/furnace recipe output metas — metas that appear as recipe outputs.
        // Catches items that are only obtainable through crafting and may not be in
        // creative tabs or OreDict (e.g., certain GT machine components).
        // 来源 3：合成/熔炉配方输出 meta —— 作为配方输出出现的 meta 值。
        // 捕获仅通过合成获得的物品，它们可能不在创造标签页或矿辞中（如某些 GT 机器部件）。
        if (null != RECIPE_OUTPUT_METAS) {
            final Set<Integer> recipeMetas = AspectUtils.RECIPE_OUTPUT_METAS.get(item);
            if (null != recipeMetas) metas.addAll(recipeMetas);
        }

        // Source 4: Fallback 0-15 — only when hasSubtypes and no meta beyond 0 was found,
        // and no high meta (>15) is present (GT etc. use high metas; do not cap at 15).
        // 来源 4：兜底 0-15 —— 仅当 hasSubtypes 且未发现 0 以外 meta，且不存在高 meta（>15）时使用。
        boolean hasHighMeta = false;
        for (final Integer m : metas) {
            if (null != m && 15 < m) {
                hasHighMeta = true;
                break;
            }
        }
        if (item.getHasSubtypes() && 1 >= metas.size() && !hasHighMeta) {
            for (int i = 0; 16 > i; i++) {
                metas.add(i);
            }
        }

        return metas;
    }

    /**
     * Finds a representative ItemStack for a Fluid so we can look up its aspects.
     * Strategy: first try the fluid's block form (e.g., flowing lava → lava block item),
     * then fall back to searching FluidContainerRegistry for any filled container
     * (e.g., a bucket of the fluid). This is needed because TC registers aspects on
     * ItemStacks, not Fluids directly.
     * <p>
     * 为流体找到一个代表性 ItemStack 以便查找其要素。
     * 策略：首先尝试流体的方块形式（如流动熔岩 → 熔岩方块物品），
     * 然后回退到在 FluidContainerRegistry 中搜索任意已填充容器（如该流体的桶）。
     * 这样做是因为 TC 在 ItemStack 上注册要素，而非直接在 Fluid 上。
     *
     * @param fluid the Fluid to find a representative for / 要查找代表物的流体
     * @return an ItemStack representing the fluid, or null if none found / 代表该流体的 ItemStack，未找到则返回 null
     */
    public static ItemStack getFluidRepresentative(final Fluid fluid) {
        final Block b = fluid.getBlock();
        if (null != b && b != Blocks.air) {
            final Item i = Item.getItemFromBlock(b);
            if (null != i) return new ItemStack(i);
        }

        for (final FluidContainerRegistry.FluidContainerData d : FluidContainerRegistry
            .getRegisteredFluidContainerData()) {
            if (null != d && null != d.fluid && d.fluid.getFluid() == fluid && null != d.filledContainer) {
                return d.filledContainer.copy();
            }
        }
        return null;
    }

    /**
     * Checks if an item behaves as food. Tests two conditions because some mods
     * use custom food items that don't extend {@link ItemFood} but still have
     * eat/drink use-actions (e.g., Pam's HarvestCraft, Spice of Life).
     * This is used by the fallback aspect system to assign food-related aspects.
     * <p>
     * 检查物品是否具有食物行为。测试两个条件，因为部分模组的自定义食物物品
     * 不继承 {@link ItemFood}，但仍具有吃/喝的使用动作（如 Pam's HarvestCraft、Spice of Life）。
     * 回退要素系统使用此方法来分配食物相关要素。
     *
     * @param item  the Item instance / Item 实例
     * @param stack the ItemStack (needed for getItemUseAction) / ItemStack（getItemUseAction 需要）
     * @return true if the item is food-like / 若物品类似食物则返回 true
     */
    public static boolean isFoodLike(final Item item, final ItemStack stack) {
        if (item instanceof ItemFood) return true;
        try {
            final EnumAction action = item.getItemUseAction(stack);
            return EnumAction.eat == action || EnumAction.drink == action;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Calls Thaumcraft's internal aspect generation algorithm ({@code ThaumcraftApiHelper.generateTags}).
     * TC may produce aspects based on the item's base material, crafting components, or
     * hard-coded rules. Used as a fallback before our own type/keyword-based derivation —
     * if TC itself can figure out aspects, we prefer its result for consistency.
     * Returns a defensive copy to avoid mutating TC's internal data.
     * <p>
     * 调用神秘时代内部的要素生成算法（{@code ThaumcraftApiHelper.generateTags}）。
     * TC 可能根据物品的基础材料、合成组件或硬编码规则生成要素。
     * 在我们自己的类型/关键词推导之前用作回退——如果 TC 自身能推导出要素，
     * 我们优先使用其结果以保持一致性。返回防御性副本以避免修改 TC 内部数据。
     *
     * @param stack the ItemStack to generate aspects for / 要生成要素的 ItemStack
     * @return a copy of TC-generated aspects, or null if TC produced nothing / TC 生成要素的副本，若 TC 无结果则返回 null
     */
    public static AspectList generateWithThaumcraft(final ItemStack stack) {
        try {
            final AspectList al = ThaumcraftApiHelper.generateTags(stack.getItem(), stack.getItemDamage());
            if (hasPositiveAspectAmount(al)) {
                return al.copy();
            }
        } catch (final Exception ignored) {}
        return null;
    }

    /**
     * Extracts ingredient lists from a vanilla or Forge recipe.
     * Handles all 4 standard recipe types:
     * <ul>
     * <li>{@link ShapedRecipes} — vanilla shaped (fixed ItemStack slots)</li>
     * <li>{@link ShapedOreRecipe} — Forge shaped (slots can be OreDict strings or Lists)</li>
     * <li>{@link ShapelessRecipes} — vanilla shapeless (fixed ItemStack list)</li>
     * <li>{@link ShapelessOreRecipe} — Forge shapeless (inputs can be OreDict strings or Lists)</li>
     * </ul>
     * Each returned inner list represents one recipe slot's alternatives (typically 1 item
     * for vanilla recipes, multiple for OreDict-based recipes).
     * <p>
     * 从原版或 Forge 配方中提取原料列表。处理全部 4 种标准配方类型：
     * <ul>
     * <li>{@link ShapedRecipes} — 原版有序合成（固定 ItemStack 槽位）</li>
     * <li>{@link ShapedOreRecipe} — Forge 有序合成（槽位可为矿辞字符串或列表）</li>
     * <li>{@link ShapelessRecipes} — 原版无序合成（固定 ItemStack 列表）</li>
     * <li>{@link ShapelessOreRecipe} — Forge 无序合成（输入可为矿辞字符串或列表）</li>
     * </ul>
     * 返回的每个内层列表代表一个配方槽位的备选项（原版配方通常为 1 个物品，矿辞配方可能有多个）。
     *
     * @param recipe the recipe to extract inputs from / 要提取输入的配方
     * @return list of slot alternatives / 槽位备选项列表
     */
    public static List<List<ItemStack>> getRecipeInputs(final IRecipe recipe) {
        List<List<ItemStack>> inputs = new ArrayList<>();

        // 1. Standard recipe types (known class structure, no reflection needed)
        // 标准配方类型（已知类结构，不需要反射）
        if (recipe instanceof ShapedRecipes) {
            final ShapedRecipes sr = (ShapedRecipes) recipe;
            for (final ItemStack s : sr.recipeItems) {
                if (null != s) inputs.add(Collections.singletonList(s.copy()));
            }
        } else if (recipe instanceof ShapedOreRecipe) {
            final ShapedOreRecipe sor = (ShapedOreRecipe) recipe;
            for (final Object o : sor.getInput()) {
                inputs.add(AspectUtils.resolveOreInput(o));
            }
        } else if (recipe instanceof ShapelessRecipes) {
            final ShapelessRecipes slr = (ShapelessRecipes) recipe;
            final List<ItemStack> items = slr.recipeItems;
            for (final ItemStack s : items) {
                if (null != s) inputs.add(Collections.singletonList(s.copy()));
            }
        } else if (recipe instanceof ShapelessOreRecipe) {
            final ShapelessOreRecipe sor = (ShapelessOreRecipe) recipe;
            for (final Object o : sor.getInput()) {
                inputs.add(AspectUtils.resolveOreInput(o));
            }
        }

        // 2. Reflection fallback for modded IRecipe implementations (GT, IC2, Forestry, AE2, etc.)
        // Many mods define custom recipe classes with getInput()/input fields containing ingredients.
        // 反射兜底：处理模组自定义 IRecipe 实现（GT、IC2、林业、AE2 等）。
        // 很多模组定义了自定义配方类，通过 getInput()/input 字段存储原料。
        if (inputs.isEmpty()) {
            inputs = AspectUtils.extractInputsViaReflection(recipe);
        }

        return inputs;
    }

    /**
     * Reflection-based ingredient extraction for unknown IRecipe implementations.
     * Tries common method names first (getInput, getIngredients, etc.), then scans
     * fields for ItemStack arrays/lists. Skips output-like fields to avoid false positives.
     * <p>
     * 基于反射的未知 IRecipe 实现原料提取。
     * 先尝试常见方法名（getInput、getIngredients 等），再扫描字段查找 ItemStack 数组/列表。
     * 跳过类似输出的字段以避免误报。
     */
    private static List<List<ItemStack>> extractInputsViaReflection(final IRecipe recipe) {
        final List<List<ItemStack>> inputs = new ArrayList<>();

        // Strategy 1: Try common getter method names that mod recipe classes often expose.
        // 策略1：尝试模组配方类常暴露的 getter 方法名。
        final String[] methodNames = { "getInput", "getIngredients", "getInputs", "getCraftingInput", "getComponents" };
        for (final String name : methodNames) {
            try {
                final Method m = recipe.getClass()
                    .getMethod(name);
                final Object result = m.invoke(recipe);
                AspectUtils.collectInputsFromObject(result, inputs);
                if (!inputs.isEmpty()) return inputs;
            } catch (final Exception e) {
                ModFileLogger.warn(
                    "[ThaumicAllAspect] Error calling " + name
                        + "() on recipe "
                        + recipe.getClass()
                            .getName()
                        + ": "
                        + e.getMessage());
            }
        }

        // Strategy 2: Scan declared fields (including superclasses) for ingredient-like data.
        // Look for arrays/lists of ItemStack or Object (which may contain OreDict strings).
        // Skip fields named "output"/"result" to avoid misidentifying recipe outputs as inputs.
        // 策略2：扫描声明的字段（包括父类），查找类似原料的数据。
        // 查找 ItemStack 或 Object 的数组/列表（可能包含矿辞字符串）。
        // 跳过名为 "output"/"result" 的字段，避免将配方输出误认为输入。
        Class<?> clazz = recipe.getClass();
        while (null != clazz && Object.class != clazz) {
            for (final Field f : clazz.getDeclaredFields()) {
                try {
                    final String fname = f.getName()
                        .toLowerCase();
                    if (fname.contains("output") || fname.contains("result")) continue;

                    f.setAccessible(true);
                    final Object val = f.get(recipe);
                    if (null == val) continue;

                    AspectUtils.collectInputsFromObject(val, inputs);
                    if (!inputs.isEmpty()) return inputs;
                } catch (final Exception e) {
                    ModFileLogger.warn(
                        "[ThaumicAllAspect] Error reading field " + f.getName()
                            + " from recipe "
                            + recipe.getClass()
                                .getName()
                            + ": "
                            + e.getMessage());
                }
            }
            clazz = clazz.getSuperclass();
        }

        return inputs;
    }

    /**
     * Extracts recipe input slots from a raw object returned by reflection.
     * Handles ItemStack[], Object[] (may contain ItemStack/String/List mix), and List types.
     * Single ItemStack values are ignored (likely recipe output, not input).
     * <p>
     * 从反射返回的原始对象中提取配方输入槽。
     * 处理 ItemStack[]、Object[]（可能包含 ItemStack/String/List 混合）和 List 类型。
     * 忽略单个 ItemStack 值（可能是配方输出而非输入）。
     */
    private static void collectInputsFromObject(final Object obj, final List<List<ItemStack>> inputs) {
        if (obj instanceof ItemStack[]) {
            final ItemStack[] arr = (ItemStack[]) obj;
            for (final ItemStack s : arr) {
                if (null != s) inputs.add(Collections.singletonList(s.copy()));
            }
        } else if (obj instanceof Object[]) {
            final Object[] arr = (Object[]) obj;
            for (final Object o : arr) {
                final List<ItemStack> resolved = AspectUtils.resolveOreInput(o);
                if (!resolved.isEmpty()) inputs.add(resolved);
            }
        } else if (obj instanceof List<?>) {
            final List<?> list = (List<?>) obj;
            if (list.isEmpty()) return;
            for (final Object o : list) {
                final List<ItemStack> resolved = AspectUtils.resolveOreInput(o);
                if (!resolved.isEmpty()) inputs.add(resolved);
            }
        }
    }

    /**
     * Recursively resolves a recipe input object to a list of concrete ItemStack candidates.
     * Recipe inputs in Forge can be heterogeneous:
     * <ul>
     * <li>{@link ItemStack} — used directly (copied for safety)</li>
     * <li>{@link Item} — wrapped with wildcard meta (any damage value)</li>
     * <li>{@link Block} — converted to its Item form with wildcard meta</li>
     * <li>{@link String} — treated as an OreDict name, resolved to all registered stacks</li>
     * <li>{@link List} — each element recursively resolved (nested OreDict alternatives)</li>
     * </ul>
     * <p>
     * 将配方输入对象递归解析为具体的 ItemStack 候选列表。Forge 中的配方输入可以是异构的：
     * <ul>
     * <li>{@link ItemStack} — 直接使用（复制以确保安全）</li>
     * <li>{@link Item} — 用通配符 meta 包装（任意损坏值）</li>
     * <li>{@link Block} — 转换为对应 Item 形式，使用通配符 meta</li>
     * <li>{@link String} — 视为矿辞名称，解析为所有已注册的物品堆</li>
     * <li>{@link List} — 每个元素递归解析（嵌套的矿辞备选项）</li>
     * </ul>
     *
     * @param input the raw recipe input object / 原始配方输入对象
     * @return list of resolved ItemStack candidates / 解析后的 ItemStack 候选列表
     */
    public static List<ItemStack> resolveOreInput(final Object input) {
        final List<ItemStack> list = new ArrayList<>();
        if (input instanceof ItemStack) list.add(((ItemStack) input).copy());
        else if (input instanceof Item) list.add(new ItemStack((Item) input, 1, OreDictionary.WILDCARD_VALUE));
        else if (input instanceof Block) {
            final Item i = Item.getItemFromBlock((Block) input);
            if (null != i) list.add(new ItemStack(i, 1, OreDictionary.WILDCARD_VALUE));
        } else if (input instanceof String) list.addAll(OreDictionary.getOres((String) input));
        else if (input instanceof List) {
            for (final Object sub : (List<?>) input) list.addAll(AspectUtils.resolveOreInput(sub));
        }
        return list;
    }

    /**
     * Converts an AspectList to a human-readable string for debug/logging output.
     * Produces "[tag=amount, tag=amount, ...]" format. Null-safe throughout:
     * handles null AspectList, null aspect array, null individual aspects, and
     * exceptions (some mods have buggy AspectList implementations).
     * <p>
     * 将 AspectList 转换为可读的调试/日志输出字符串。
     * 输出格式为 "[标签=数量, 标签=数量, ...]"。全程空安全：
     * 处理 null AspectList、null 要素数组、null 单个要素以及异常
     * （部分模组的 AspectList 实现存在 bug）。
     *
     * @param al the AspectList to convert / 要转换的 AspectList
     * @return human-readable string representation / 可读的字符串表示
     */
    public static String aspectListToString(final AspectList al) {
        if (null == al) return "[null]";
        try {
            final Aspect[] aspects = al.getAspects();
            if (null == aspects || 0 == aspects.length) return "[empty, size=" + al.size() + "]";
            final StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (final Aspect a : aspects) {
                if (null == a) continue;
                if (!first) sb.append(", ");
                sb.append(a.getTag())
                    .append("=")
                    .append(al.getAmount(a));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } catch (final Exception e) {
            return "[error: " + e.getMessage() + "]";
        }
    }

    /**
     * Loads aspect cache from a file (e.g. config/ThaumicAllAspect-aspect-cache.cfg).
     * Each line: {@code ItemID@meta = tag1=amount, tag2=amount, ...}
     * Registers to ThaumcraftApi and puts into CACHE. Used so server can get full aspects
     * (including Botania etc.) without reflecting mod recipe classes.
     * 从文件加载要素缓存，注册到 TC 并写入 CACHE，供服务器免反射获得完整要素。
     *
     * @return number of entries loaded
     */
    public static int loadAspectCacheFromFile(final File file) {
        if (null == file || !file.isFile()) return 0;
        int count = 0;
        try (final BufferedReader r = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while (null != (line = r.readLine())) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final int eq = line.indexOf(" = ");
                if (0 >= eq) continue;
                final String key = line.substring(0, eq)
                    .trim();
                final String val = line.substring(eq + 3)
                    .trim();
                final int at = key.indexOf('@');
                if (0 >= at) continue;
                final String regName = key.substring(0, at);
                final int meta;
                try {
                    meta = Integer.parseInt(key.substring(at + 1));
                } catch (final NumberFormatException e) {
                    continue;
                }
                final Object itemObj = Item.itemRegistry.getObject(regName);
                if (!(itemObj instanceof Item)) continue;
                final Item item = (Item) itemObj;
                final AspectList al = AspectUtils.ensureMinOnePerAspect(AspectUtils.parseAspectList(val));
                if (null == al || 0 == al.size()) continue;
                final ItemStack stack = new ItemStack(item, 1, meta);
                ThaumcraftApi.registerObjectTag(stack, al.copy());
                AspectUtils.CACHE.put(key, al.copy());
                count++;
            }
        } catch (final IOException e) {
            ModFileLogger.warn("[ThaumicAllAspect] " + "Error loading aspect cache: " + e.getMessage());
        }
        return count;
    }

    /**
     * Public parse for config fallbacks (same format as cache file).
     */
    public static AspectList parseAspectListPublic(final String s) {
        return AspectUtils.parseAspectList(s);
    }

    private static AspectList parseAspectList(final String s) {
        if (null == s || s.isEmpty()) return null;
        final AspectList al = new AspectList();
        for (String part : s.split(",")) {
            part = part.trim();
            final int eq = part.indexOf('=');
            if (0 >= eq) continue;
            final String tag = part.substring(0, eq)
                .trim();
            final int amt;
            try {
                amt = Integer.parseInt(
                    part.substring(eq + 1)
                        .trim());
            } catch (final NumberFormatException e) {
                continue;
            }
            final Aspect a = AspectUtils.getAspect(tag);
            if (null != a && 0 < amt) al.add(a, amt);
        }
        return 0 < al.size() ? al : null;
    }
}
