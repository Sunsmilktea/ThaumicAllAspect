package com.sunmilktea.thaumicallaspect.aspect.scan;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

import com.sunmilktea.thaumicallaspect.aspect.derive.AspectDeriver;
import com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils;
import com.sunmilktea.thaumicallaspect.aspect.modbridge.ModRecipeBridge;
import com.sunmilktea.thaumicallaspect.aspect.modbridge.NEIRecipeAdapter;
import com.sunmilktea.thaumicallaspect.config.FallbackConfig;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.ThaumcraftApiHelper;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.IArcaneRecipe;
import thaumcraft.api.crafting.InfusionRecipe;

/**
 * Main scan orchestrator — the central controller that drives the entire aspect derivation pipeline.
 * 主扫描调度器 —— 驱动整个要素推导流水线的核心控制器。
 *
 * <p>
 * The scanning architecture follows a 6-phase pipeline design:
 * </p>
 * <p>
 * 扫描架构采用 6 阶段流水线设计：
 * </p>
 *
 * <ol>
 * <li><b>Phase 1 — Index Building (索引构建):</b>
 * Builds 4 pre-computed lookup indexes for O(1) recipe/OreDict lookup during derivation:
 * 构建 4 个预计算查找索引，使推导过程中配方/矿辞查找达到 O(1) 复杂度：
 * <ul>
 * <li>OreDict metas index — which metadata values each Item is registered with in Forge OreDictionary
 * 矿辞元数据索引 — 每个 Item 在 Forge OreDictionary 中注册了哪些 metadata 值</li>
 * <li>Crafting recipe index — maps output Item → List of IRecipe for crafting table recipes
 * 合成配方索引 — 将输出 Item 映射到工作台合成配方列表</li>
 * <li>Furnace recipe index — maps output Item → List of smelting entries
 * 熔炉配方索引 — 将输出 Item 映射到熔炼配方条目列表</li>
 * <li>Thaumcraft recipe index — maps output Item → List of TC-specific recipe objects (arcane, infusion, crucible)
 * 神秘时代配方索引 — 将输出 Item 映射到 TC 专属配方对象列表（奥术、注魔、坩埚）</li>
 * </ul>
 * </li>
 * <li><b>Phase 2 — Item Collection (物品收集):</b>
 * Collects all items from {@code Item.itemRegistry} and {@code Block.blockRegistry},
 * grouped by mod ID and sorted alphabetically for deterministic, reproducible scan order.
 * 从 {@code Item.itemRegistry} 和 {@code Block.blockRegistry} 收集所有物品，
 * 按模组 ID 分组并按字母排序，确保扫描顺序确定且可复现。</li>
 * <li><b>Phase 3 — Pass 1 Scan (第一轮扫描):</b>
 * Iterates all items; skips those that already have TC aspects registered;
 * derives aspects for the rest via recipe decomposition, OreDict mapping, or fallback heuristics.
 * Each successful derivation is immediately registered so subsequent items can reference it.
 * 遍历所有物品；跳过已有 TC 要素的物品；通过配方分解、矿辞映射或回退启发式方法为其余物品推导要素。
 * 每次成功推导后立即注册，使后续物品可以引用它。</li>
 * <li><b>Phase 4 — Multi-pass Retry (多轮重试):</b>
 * Items that failed in pass 1 may succeed after other items in their dependency chain
 * got aspects registered. Retries up to 5 additional passes; stops early when a pass
 * produces zero new registrations (fixed point reached).
 * 第一轮失败的物品可能在其依赖链中的其他物品获得要素后成功推导。
 * 最多重试 5 轮；当某轮未产生新注册时提前停止（到达不动点）。</li>
 * <li><b>Phase 5 — Fluid Scanning (流体扫描):</b>
 * Separate pass for fluids using {@code FluidRegistry}, since fluids are not in
 * {@code Item.itemRegistry}. Tries smart OreDict-based derivation first, then standard chain.
 * 使用 {@code FluidRegistry} 对流体单独扫描，因为流体不在 {@code Item.itemRegistry} 中。
 * 优先尝试基于矿辞的智能推导，然后走标准推导链。</li>
 * <li><b>Phase 6 — Finalization (收尾):</b>
 * Computes statistics, logs failures, dumps the full aspect cache to disk,
 * and dumps the full aspect cache to disk.
 * 统计数据、记录失败项、将完整要素缓存导出到磁盘。</li>
 * </ol>
 */
public enum AspectScanner {
    ;

    private static final File VERIFY_CONFIG = new File("config/ThaumicAllAspect", "verify.cfg");

    // ==================== User-defined Verification / 用户自定义验证 ====================

    /**
     * Entry point: runs the full aspect scanning pipeline.
     * 入口：运行完整的要素扫描流水线。
     *
     * <p>
     * Called from {@code ThaumicAllAspect.onLoadComplete} (FML LoadComplete event),
     * which fires after ALL mods have finished their init/postInit phases.
     * This timing is critical — it guarantees that every item, block, recipe, and
     * OreDict entry from every mod is fully registered and available for scanning.
     * </p>
     * <p>
     * 由 {@code ThaumicAllAspect.onLoadComplete}（FML LoadComplete 事件）调用，
     * 该事件在所有模组完成 init/postInit 阶段后触发。
     * 这个时机至关重要 —— 它保证了每个模组的所有物品、方块、配方和矿辞条目都已完全注册并可用于扫描。
     * </p>
     *
     * <p>
     * <b>Output files (输出文件):</b>
     * </p>
     * <ul>
     * <li>{@code ThaumicAllAspect-scan.log} — detailed per-item scan log with derivation paths
     * 每个物品的详细扫描日志，含推导路径</li>
     * <li>{@code ThaumicAllAspect-cache.cfg} — all derived aspects in a reloadable config format
     * 所有推导出的要素，以可重载配置格式存储</li>
     * <li>{@code ThaumicAllAspect-failures.txt} — registry IDs of items that failed all derivation attempts
     * 所有推导尝试均失败的物品注册 ID</li>
     * </ul>
     */
    public static void scanAndAssignAspects() {
        AspectUtils.CACHE.clear();
        AspectUtils.FAILED_IDS.clear();
        AspectUtils.statAlreadyHad = 0;
        AspectUtils.statNewlyRegistered = 0;
        AspectUtils.statNoAspect = 0;

        final long tGlobal = System.currentTimeMillis();
        ModFileLogger.info("========== [ThaumicAllAspect] " + tr("Starting full scan") + " ==========");
        ModFileLogger.beginScanLog();

        // All ThaumicAllAspect configs now live under config/ThaumicAllAspect/
        final File configDir = new File("config", "ThaumicAllAspect");
        if (!configDir.exists()) {
            // Best-effort directory creation; failure just means we fall back to old behaviour.
            // 尽力创建配置目录；失败时仅意味着回退到旧行为。
            // noinspection ResultOfMethodCallIgnored
            configDir.mkdirs();
        }

        // Load aspect cache from config as an initial seed so servers can ship a precomputed baseline
        // (e.g. from dev environment). Even when a cache is present, we still run the full pipeline
        // once per startup to pick up new recipes / items, but never overwrite existing aspects.
        // 从配置中加载要素缓存作为初始种子（例如开发环境预先生成的基线）。
        // 即使存在缓存，每次启动仍会跑一遍完整流水线以捕获新增配方/物品，但不会覆盖已有要素。
        final File configCache = new File(configDir, "aspect-cache.cfg");
        if (configCache.isFile()) {
            final int n = AspectUtils.loadAspectCacheFromFile(configCache);
            if (0 < n) {
                ModFileLogger.info(
                    "[ThaumicAllAspect] Loaded " + n
                        + " aspect entries from config cache (config/ThaumicAllAspect/aspect-cache.cfg).");
            }
        }

        AspectScanner.buildOreDictIndex();
        // Load user fallbacks (keyword + item/block/fluid) from config/ThaumicAllAspect/
        FallbackConfig.load(configDir);
        FallbackConfig.applyItemFallbacksToCache();
        // Always build vanilla/TC indices first, then let NEI enrich/override where available.
        AspectScanner.buildCraftingRecipeIndex();
        AspectScanner.buildFurnaceIndex();
        AspectScanner.buildTCRecipeIndex();
        // NEI integration: merge additional recipes on top of existing indexes (do NOT replace).
        NEIRecipeAdapter.fillFromNEI();

        // --- Phase 2: Collect all items/blocks, grouped by mod ID ---
        // --- 第 2 阶段：收集所有物品/方块，按模组 ID 分组 ---
        //
        // We collect from BOTH Item.itemRegistry AND Block.blockRegistry because some blocks
        // (e.g., technical blocks, certain mod blocks) only exist in the block registry and have
        // no corresponding Item registration. Block.blockRegistry entries are converted to Items
        // via Item.getItemFromBlock, which returns null for blocks with no item form (those are skipped).
        // 我们同时从 Item.itemRegistry 和 Block.blockRegistry 收集，因为某些方块（如技术方块、特定模组方块）
        // 仅存在于方块注册表中，没有对应的 Item 注册。Block.blockRegistry 条目通过 Item.getItemFromBlock
        // 转换为 Item，对于没有物品形式的方块该方法返回 null（这些会被跳过）。
        //
        // LinkedHashSet preserves insertion order and deduplicates (same Item from both registries
        // is only kept once). TreeMap sorts mod IDs alphabetically for deterministic, reproducible
        // scan order — this makes logs diffable across runs and easier to debug.
        // LinkedHashSet 保持插入顺序并去重（两个注册表中的同一 Item 只保留一次）。
        // TreeMap 按模组 ID 字母排序以确保扫描顺序确定且可复现 —— 这使日志在不同运行间可比较，更易调试。
        final Set<Item> allItems = new LinkedHashSet<>();
        final TreeMap<String, List<Item>> modItemMap = new TreeMap<>();

        for (final Object o : Item.itemRegistry) {
            final Item item = (Item) o;
            if (null == item) continue;
            allItems.add(item);
        }
        for (final Object o : Block.blockRegistry) {
            final Block block = (Block) o;
            if (null == block || block == Blocks.air) continue;
            final Item item = Item.getItemFromBlock(block);
            if (null != item) allItems.add(item);
        }

        for (final Item item : allItems) {
            final Object nameObj = Item.itemRegistry.getNameForObject(item);
            if (null == nameObj) continue;
            final String regName = nameObj.toString();
            final String modId = regName.contains(":") ? regName.substring(0, regName.indexOf(':')) : "minecraft";
            List<Item> list = modItemMap.get(modId);
            if (null == list) {
                list = new ArrayList<>();
                modItemMap.put(modId, list);
            }
            list.add(item);
        }

        final int totalItems = allItems.size();
        final int totalMods = modItemMap.size();
        ModFileLogger.info(
            tr("[Scan]") + " "
                + tr("Registry total:")
                + " "
                + totalItems
                + " "
                + tr("items/blocks from")
                + " "
                + totalMods
                + " "
                + tr("mods"));

        // --- Phase 3a: Recipe-first pipeline (up to 6 rounds) ---
        // 无论是否存在缓存：先按配方迭代最多 6 轮，为所有「输入已齐」的配方产出赋源质。
        RecipeFirstAspectPipeline.run();

        // --- Phase 3b: Single item pass — fill remaining via OreDict / type / keyword fallback ---
        // 再遍历一遍物品：已有要素的跳过；仍未有的用 OreDict / 类型 / 关键词兜底推导。
        final List<ItemStack> pass1Failed = new ArrayList<>();
        int modIndex = 0;
        for (final Map.Entry<String, List<Item>> modEntry : modItemMap.entrySet()) {
            modIndex++;
            final String modId = modEntry.getKey();
            final List<Item> items = modEntry.getValue();
            int modReg = 0, modSkip = 0, modFail = 0;

            for (final Item item : items) {
                final String id;
                try {
                    id = Item.itemRegistry.getNameForObject(item);
                } catch (final Exception e) {
                    continue;
                }
                boolean hasAny = false;

                final Set<Integer> metas;
                try {
                    metas = AspectUtils.getMetasToScan(item);
                } catch (final Exception e) {
                    ModFileLogger
                        .scan(tr("[Error]") + " " + id + " " + tr("failed to get metas:") + " " + e.getMessage());
                    continue;
                }

                for (final int meta : metas) {
                    try {
                        final ItemStack stack = new ItemStack(item, 1, meta);

                        // Check TC registration and pre-populate CACHE in one step
                        // (avoids calling getObjectAspects twice — once in hasAspect, once for cache).
                        // 一步完成 TC 注册检查和缓存预填充
                        // （避免调用两次 getObjectAspects——hasAspect 中一次，缓存一次）。
                        if (ThaumcraftApi.exists(stack.getItem(), stack.getItemDamage())) {
                            final AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                            if (null != existing && 0 < existing.size()) {
                                AspectUtils.CACHE.put(AspectUtils.key(stack), existing.copy());
                                hasAny = true;
                                AspectUtils.statAlreadyHad++;
                                modSkip++;
                                continue;
                            }
                        }

                        AspectUtils.lastDerivePath = "";
                        final AspectList aspects = AspectDeriver
                            .getOrGenerateAspectsFor(stack, 0, new HashSet<String>());
                        if (null != aspects && 0 < aspects.size()) {
                            final String aspectStr = AspectUtils.aspectListToString(aspects);
                            String displayName;
                            try {
                                displayName = stack.getDisplayName();
                            } catch (final Exception e) {
                                displayName = "?";
                            }
                            ThaumcraftApi.registerObjectTag(
                                stack,
                                AspectUtils.ensureMinOnePerAspect(aspects)
                                    .copy());
                            AspectUtils.statNewlyRegistered++;
                            modReg++;
                            hasAny = true;
                            ModFileLogger.scan(
                                tr("[Register]") + " "
                                    + id
                                    + ":"
                                    + meta
                                    + " ("
                                    + displayName
                                    + ") <- "
                                    + aspectStr
                                    + " [via "
                                    + AspectUtils.lastDerivePath
                                    + "]");
                        } else {
                            modFail++;
                            pass1Failed.add(stack);
                            ModFileLogger.scan(
                                tr("[Retry pending]") + " "
                                    + id
                                    + ":"
                                    + meta
                                    + " <- "
                                    + tr("no aspects derived in pass 1"));
                        }
                    } catch (final Exception e) {
                        ModFileLogger.scan(
                            tr("[Crash]") + " "
                                + id
                                + ":"
                                + meta
                                + " <- "
                                + e.getClass()
                                    .getSimpleName()
                                + ": "
                                + e.getMessage());
                        modFail++;
                    }
                }

                if (!hasAny) {
                    AspectUtils.FAILED_IDS.add(id);
                }
            }

            final String modSummary = tr("[Scan]") + " ("
                + modIndex
                + "/"
                + totalMods
                + ") "
                + modId
                + " | "
                + tr("items")
                + "="
                + items.size()
                + " "
                + tr("skipped")
                + "="
                + modSkip
                + " "
                + tr("registered")
                + "="
                + modReg
                + " "
                + tr("failed")
                + "="
                + modFail;
            ModFileLogger.info(modSummary);
            ModFileLogger.scanSummary("");
            ModFileLogger.scanSummary(modSummary);
        }

        // --- Phase 4: Multi-pass retry — resolve transitive dependency chains ---
        // --- 第 4 阶段：多轮重试 — 解析传递性依赖链 ---
        //
        // WHY multi-pass is needed (为什么需要多轮):
        // Some items depend on other items that ALSO need derivation. Consider this chain:
        // amber brick → crafted from amber block → crafted from amber item
        // 某些物品依赖于同样需要推导的其他物品。考虑这条链：
        // 琥珀砖 → 由琥珀块合成 → 由琥珀物品合成
        //
        // In pass 1, when amber brick tries to derive from its crafting recipe, amber block
        // might not have aspects yet (it was also unknown). So amber brick fails.
        // But amber item (a raw material) got aspects from fallback/OreDict in pass 1.
        // 在第一轮中，当琥珀砖尝试从其合成配方推导时，琥珀块可能还没有要素（它也是未知的）。
        // 所以琥珀砖失败了。但琥珀物品（原材料）在第一轮中通过回退/矿辞获得了要素。
        //
        // In pass 2, amber block can now derive from its recipe (amber item has aspects),
        // and amber brick might still fail if amber block just got registered.
        // In pass 3, amber brick finally succeeds because amber block now has aspects.
        // 在第二轮中，琥珀块现在可以从其配方推导（琥珀物品已有要素），
        // 而琥珀砖可能仍然失败（如果琥珀块刚被注册）。
        // 在第三轮中，琥珀砖终于成功，因为琥珀块现在有要素了。
        //
        // Cache is cleared between passes to force re-derivation with newly available aspect data;
        // stale "null" cache entries from failed lookups in earlier passes must not block success.
        // 每轮之间清除缓存以强制使用新可用的要素数据重新推导；
        // 早期轮次中失败查找产生的过时 "null" 缓存条目不能阻碍成功。
        //
        // Stops early if a pass produces zero new registrations — this means we've reached a
        // fixed point where no further progress is possible (remaining items are truly underivable).
        // After recipe-first 8-round pipeline, most dependencies are resolved; 2 retries suffice for fallback-only
        // chains.
        // 配方优先 8 轮后多数依赖已解决；2 轮重试足以覆盖仅靠兜底的链。
        final int maxRetryPasses = 2;
        List<ItemStack> retryList = pass1Failed;

        for (int pass = 2; pass <= maxRetryPasses + 1 && !retryList.isEmpty(); pass++) {
            // DO NOT clear cache: it only contains successfully derived aspects (never null/empty).
            // Clearing it would destroy all work from previous passes, forcing redundant re-derivation.
            // Failed items are not cached, so there are no stale entries to worry about.
            // 不清除缓存：缓存中只有成功推导的要素（绝不会是 null/空）。
            // 清除它会销毁之前轮次的所有工作，强制进行冗余的重新推导。
            // 失败的物品不会被缓存，因此不存在过时条目的问题。
            ModFileLogger.info(
                tr("[Pass") + " "
                    + pass
                    + "] "
                    + tr("Retrying")
                    + " "
                    + retryList.size()
                    + " "
                    + tr("items")
                    + " ("
                    + tr("cache entries")
                    + ": "
                    + AspectUtils.CACHE.size()
                    + ")...");
            ModFileLogger.scanSummary("");
            ModFileLogger.scanSummary(
                "========== " + tr("[Pass")
                    + " "
                    + pass
                    + "] "
                    + tr("Retrying")
                    + " "
                    + retryList.size()
                    + " "
                    + tr("items")
                    + " ==========");

            final List<ItemStack> stillFailed = new ArrayList<>();
            int passReg = 0, passFail = 0;

            for (final ItemStack stack : retryList) {
                final String id;
                try {
                    id = Item.itemRegistry.getNameForObject(stack.getItem());
                } catch (final Exception e) {
                    continue;
                }
                final int meta = stack.getItemDamage();

                try {
                    // Re-check: item may have been registered by a previous pass
                    // 重新检查：物品可能已在之前的轮次中被注册
                    if (ThaumcraftApi.exists(stack.getItem(), stack.getItemDamage())) {
                        final AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                        if (null != existing && 0 < existing.size()) {
                            AspectUtils.CACHE.put(AspectUtils.key(stack), existing.copy());
                            passReg++;
                            AspectUtils.statAlreadyHad++;
                            AspectUtils.FAILED_IDS.remove(id);
                            continue;
                        }
                    }

                    AspectUtils.lastDerivePath = "";
                    final AspectList aspects = AspectDeriver.getOrGenerateAspectsFor(stack, 0, new HashSet<String>());
                    if (null != aspects && 0 < aspects.size()) {
                        final String aspectStr = AspectUtils.aspectListToString(aspects);
                        String displayName;
                        try {
                            displayName = stack.getDisplayName();
                        } catch (final Exception e) {
                            displayName = "?";
                        }
                        ThaumcraftApi.registerObjectTag(
                            stack,
                            AspectUtils.ensureMinOnePerAspect(aspects)
                                .copy());
                        AspectUtils.statNewlyRegistered++;
                        passReg++;
                        AspectUtils.FAILED_IDS.remove(id);
                        ModFileLogger.scan(
                            tr("[Pass") + " "
                                + pass
                                + " "
                                + tr("register")
                                + "] "
                                + id
                                + ":"
                                + meta
                                + " ("
                                + displayName
                                + ") <- "
                                + aspectStr
                                + " [via "
                                + AspectUtils.lastDerivePath
                                + "]");
                    } else {
                        passFail++;
                        stillFailed.add(stack);
                    }
                } catch (final Exception e) {
                    passFail++;
                    stillFailed.add(stack);
                    ModFileLogger.scan(
                        tr("[Pass") + " "
                            + pass
                            + " "
                            + tr("crash")
                            + "] "
                            + id
                            + ":"
                            + meta
                            + " <- "
                            + e.getClass()
                                .getSimpleName()
                            + ": "
                            + e.getMessage());
                }
            }

            final String passSummary = tr("[Pass") + " "
                + pass
                + "] "
                + tr("Done:")
                + " "
                + tr("registered")
                + "="
                + passReg
                + " "
                + tr("still failed")
                + "="
                + passFail
                + " ("
                + tr("input")
                + " "
                + retryList.size()
                + " "
                + tr("items")
                + ")";
            ModFileLogger.info(passSummary);
            ModFileLogger.scanSummary(passSummary);

            if (0 == passReg) {
                final String stopMsg = tr("[Retry]") + " " + tr("No new registrations this pass, stopping");
                ModFileLogger.info(stopMsg);
                ModFileLogger.scanSummary(stopMsg);
                break;
            }

            retryList = stillFailed;
        }

        if (!retryList.isEmpty()) {
            AspectUtils.statNoAspect += retryList.size();
        }

        // --- Phase 5: Fluid scanning ---
        // --- 第 5 阶段：流体扫描 ---
        //
        // Fluids are scanned separately because they live in FluidRegistry, not Item.itemRegistry.
        // For each fluid, we find a representative ItemStack (block form or filled container)
        // and try two derivation strategies:
        // 1. deriveFluidFromMaterial — smart OreDict-based derivation for molten metals
        // (e.g., fluid "gold" → looks up "ingotGold" → derives from gold ingot aspects)
        // 2. Standard getOrGenerateAspectsFor chain as fallback
        // 流体单独扫描，因为它们存在于 FluidRegistry 中，而不是 Item.itemRegistry。
        // 对于每种流体，我们找到一个代表性 ItemStack（方块形式或已装填容器），
        // 并尝试两种推导策略：
        // 1. deriveFluidFromMaterial — 基于矿辞的熔融金属智能推导
        // （如流体 "gold" → 查找 "ingotGold" → 从金锭要素推导）
        // 2. 标准 getOrGenerateAspectsFor 推导链作为回退
        AspectScanner.scanFluids();

        // --- Phase 6: Mod-specific recipe scanning ---
        // --- 第 6 阶段：模组自定义配方扫描 ---
        //
        // Some mods (AbyssalCraft, etc.) use their own recipe registries outside of CraftingManager.
        // This phase accesses those registries via reflection to derive aspects for items that
        // the standard scanning phases missed. Only targets items that STILL have no aspects.
        // 部分模组（深渊国度等）在 CraftingManager 之外使用自己的配方注册表。
        // 此阶段通过反射访问这些注册表，为标准扫描阶段遗漏的物品推导要素。
        // 仅针对仍然没有要素的物品。
        ModRecipeBridge.scanModSpecificRecipes();

        // --- Post-scan FAILED_IDS re-verification ---
        // TC's lazy aspect generation (generateTags) may have given aspects to items that
        // were still in FAILED_IDS. Re-check each failed ID and remove if aspects now exist.
        // This also ensures the cache is up-to-date for items resolved via lazy generation.
        // TC 的延迟要素生成（generateTags）可能给 FAILED_IDS 中的物品补上了要素。
        // 重新检查每个失败 ID，如果现在有要素则移除。同时更新这些物品的缓存。
        {
            int recovered = 0;
            final Iterator<String> failIter = AspectUtils.FAILED_IDS.iterator();
            while (failIter.hasNext()) {
                final String failedId = failIter.next();
                try {
                    if (failedId.startsWith("fluid:")) continue;
                    final Object itemObj = Item.itemRegistry.getObject(failedId);
                    if (!(itemObj instanceof Item)) continue;
                    final Item item = (Item) itemObj;
                    boolean found = false;
                    for (int meta = 0; 15 >= meta; meta++) {
                        final ItemStack stack = new ItemStack(item, 1, meta);
                        if (AspectUtils.hasAspect(stack)) {
                            final AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                            if (null != existing && 0 < existing.size()) {
                                AspectUtils.CACHE.put(AspectUtils.key(stack), existing.copy());
                            }
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        failIter.remove();
                        recovered++;
                    }
                } catch (final Exception ignored) {}
            }
            if (0 < recovered) {
                final String msg = tr("[Post-scan]") + " "
                    + tr("Recovered")
                    + " "
                    + recovered
                    + " "
                    + tr("items from failure list (TC lazy generation)");
                ModFileLogger.info(msg);
                ModFileLogger.scanSummary(msg);
            }
        }

        // --- Post-scan meta inheritance sweep ---
        // After all scanning is done, some items may have aspects for certain metas but not others
        // (e.g., TC lazy generation gave meta 0 aspects but meta 1 was already scanned without success).
        // This sweep propagates aspects from metas that have them to metas that don't (no decay).
        // 全部扫描完成后，部分物品某些 meta 有要素但其他 meta 没有
        // （如 TC 延迟生成给了 meta 0 要素，但 meta 1 已经扫描失败）。
        // 此补扫将有要素的 meta 传播到没有要素的 meta（无衰减）。
        {
            int metaFixed = 0;
            final Map<String, AspectList> bestByItem = new HashMap<>();

            for (final Map.Entry<String, AspectList> entry : AspectUtils.CACHE.entrySet()) {
                final String key = entry.getKey();
                final int atIdx = key.indexOf('@');
                if (0 > atIdx) continue;
                final String baseName = key.substring(0, atIdx);
                final AspectList al = entry.getValue();
                if (null == al || 0 == al.size()) continue;
                final int score = AspectUtils.getAspectTotal(al);

                final AspectList existing = bestByItem.get(baseName);
                if (null == existing || AspectUtils.getAspectTotal(existing) < score) {
                    bestByItem.put(baseName, al.copy());
                }
            }

            // Iterate only over base names that have a donor (from CACHE). Same set of items get
            // donor applied as before (previously we skipped when donor==null); no behavior change.
            // 仅遍历有供体的 baseName，与原先“仅对 donor 非空时处理”等价，不改变功能。
            for (final Map.Entry<String, AspectList> entry : bestByItem.entrySet()) {
                final String name = entry.getKey();
                final AspectList donor = entry.getValue();
                if (null == donor || 0 == donor.size()) continue;

                final Object itemObj = Item.itemRegistry.getObject(name);
                if (!(itemObj instanceof Item)) continue;
                final Item item = (Item) itemObj;

                Set<Integer> metas;
                try {
                    metas = AspectUtils.getMetasToScan(item);
                } catch (final Exception e) {
                    metas = new HashSet<>();
                    metas.add(0);
                }

                for (final int meta : metas) {
                    final ItemStack stack = new ItemStack(item, 1, meta);
                    if (AspectUtils.hasAspect(stack)) continue;

                    ThaumcraftApi.registerObjectTag(
                        stack,
                        AspectUtils.ensureMinOnePerAspect(donor)
                            .copy());
                    AspectUtils.CACHE.put(AspectUtils.key(stack), donor.copy());
                    metaFixed++;
                }
            }

            if (0 < metaFixed) {
                final String msg = tr("[Post-scan]") + " "
                    + tr("Meta inheritance sweep: fixed")
                    + " "
                    + metaFixed
                    + " "
                    + tr("item metas");
                ModFileLogger.info(msg);
                ModFileLogger.scanSummary(msg);
            }
        }

        // --- Post-scan nugget/粒 adjustment ---
        // Some very small items (e.g., "xxx 粒" or "xxx nugget") end up with aspects where
        // every entry is exactly 1, which in the TC UI renders as icons without numbers.
        // To make these more readable, we bump any pure-1 lists for "粒"/"nugget"-like items
        // so that each aspect has at least 2 points.
        // 部分粒状物品（如名称包含“粒”或 "nugget"）最终会得到所有要素都为 1 点的配置，
        // 在 TC 界面中会只显示图标而不显示数字。为提高可读性，这里对这类物品做一次补正：
        // 若所有要素数量都为 1，则将每种要素提升到至少 2 点。
        {
            int nuggetAdjusted = 0;
            for (final Map.Entry<String, AspectList> entry : AspectUtils.CACHE.entrySet()) {
                final String key = entry.getKey(); // modid:item@meta
                if (null == key) continue;
                final int atIdx = key.indexOf('@');
                if (0 >= atIdx) continue;
                final String regName = key.substring(0, atIdx);
                final String metaStr = key.substring(atIdx + 1);
                final Object itemObj = Item.itemRegistry.getObject(regName);
                if (!(itemObj instanceof Item)) continue;
                final Item item = (Item) itemObj;
                final int meta;
                try {
                    meta = Integer.parseInt(metaStr);
                } catch (final NumberFormatException ignored) {
                    continue;
                }

                final AspectList al = entry.getValue();
                if (null == al || 0 == al.size()) continue;
                final Aspect[] aspects = al.getAspects();
                if (null == aspects || 0 == aspects.length) continue;

                String displayName;
                try {
                    displayName = new ItemStack(item, 1, meta).getDisplayName();
                } catch (final Exception e) {
                    displayName = "";
                }

                final String lowerName = ((null != displayName ? displayName : "") + " "
                    + (null != regName ? regName : "")).toLowerCase();

                // 扩展“小份物品”关键字匹配：只对这些小颗粒/碎片类物品做 1→2 的提升
                boolean isSmallPiece = lowerName.contains("粒") || lowerName.contains("片")
                    || lowerName.contains("粉")
                    || lowerName.contains("末")
                    || lowerName.contains("晶")
                    || lowerName.contains("碎")
                    || lowerName.contains("屑")
                    || lowerName.contains("珠")
                    || lowerName.contains("滴")
                    || lowerName.contains("点")
                    || lowerName.contains("nugget")
                    || lowerName.contains("shard")
                    || lowerName.contains("dust")
                    || lowerName.contains("powder")
                    || lowerName.contains("crystal")
                    || lowerName.contains("gem")
                    || lowerName.contains("bead")
                    || lowerName.contains("pellet")
                    || lowerName.contains("flake")
                    || lowerName.contains("chip")
                    || lowerName.contains("sliver")
                    || lowerName.contains("mote")
                    || lowerName.contains("speck");

                if (!isSmallPiece) continue;

                boolean allOne = true;
                for (final Aspect a : aspects) {
                    if (null == a) continue;
                    final int amt = al.getAmount(a);
                    if (amt != 1) {
                        allOne = false;
                        break;
                    }
                }
                if (!allOne) continue;

                // 将所有要素从 1 提升到 2
                for (final Aspect a : aspects) {
                    if (null == a) continue;
                    if (al.getAmount(a) == 1) {
                        al.add(a, 1);
                    }
                }

                // 重新注册到 TC 并写回缓存
                final ItemStack stack = new ItemStack(item, 1, meta);
                ThaumcraftApi.registerObjectTag(stack, al.copy());
                AspectUtils.CACHE.put(key, al);
                nuggetAdjusted++;
            }

            if (0 < nuggetAdjusted) {
                final String msg = tr("[Post-scan]") + " "
                    + tr("Adjusted nugget-like items to min amount 2:")
                    + " "
                    + nuggetAdjusted;
                ModFileLogger.info(msg);
                ModFileLogger.scanSummary(msg);
            }
        }

        // Summary statistics / 统计总结
        final String[] stats = { "", tr("[Stats]") + " ===== " + tr("Full scan summary") + " =====", tr(
            "[Stats]") + " " + tr("Total items/blocks:") + " " + totalItems + " (" + totalMods + " " + tr("mods") + ")",
            tr("[Stats]") + " " + tr("Already had aspects (skipped):") + " " + AspectUtils.statAlreadyHad,
            tr("[Stats]") + " " + tr("Newly registered:") + " " + AspectUtils.statNewlyRegistered,
            tr("[Stats]") + " "
                + tr("Mod-specific recipes registered:")
                + " "
                + ModRecipeBridge.statModRecipeRegistered,
            tr("[Stats]") + " " + tr("Derivation failed (no aspects):") + " " + AspectUtils.statNoAspect,
            tr("[Stats]") + " " + tr("Cache entries:") + " " + AspectUtils.CACHE.size(),
            tr("[Stats]") + " "
                + tr("Fully failed items (no meta has aspects):")
                + " "
                + AspectUtils.FAILED_IDS.size() };
        for (final String s : stats) {
            ModFileLogger.info(s);
            ModFileLogger.scanSummary(s);
        }

        if (!AspectUtils.FAILED_IDS.isEmpty()) {
            ModFileLogger.warn("[ThaumicAllAspect] " + tr("The following items/blocks/fluids still have no aspects:"));
            ModFileLogger.scanSummary("");
            ModFileLogger
                .scanSummary(tr("[Failures]") + " " + tr("The following items/blocks/fluids still have no aspects:"));
            for (final String id : AspectUtils.FAILED_IDS) {
                ModFileLogger.warn(" - " + id);
                ModFileLogger.scanSummary(" - " + id);
            }
            ModFileLogger.appendFailureIds(
                "[ThaumicAllAspect] " + tr("Failed item/block/fluid scan IDs:"),
                AspectUtils.FAILED_IDS);
        }

        // Dump aspect cache to file / 导出要素缓存文件
        ModFileLogger.writeCacheFile(AspectUtils.CACHE);
        ModFileLogger.writeCacheFile(
            AspectUtils.CACHE,
            new File(new File("config", "ThaumicAllAspect"), "aspect-cache.cfg"),
            false);

        // User-defined verification from config / 用户自定义验证（来自配置文件）
        AspectScanner.runVerifyFromConfig();

        final long totalMs = System.currentTimeMillis() - tGlobal;
        final String doneMsg = "========== [ThaumicAllAspect] " + tr("Full scan complete, total time")
            + " "
            + totalMs
            + " ms ==========";
        ModFileLogger.info(doneMsg);
        ModFileLogger.scanSummary(doneMsg);
        ModFileLogger.endScanLog();
    }

    /**
     * Reads user-defined verification entries from {@code config/ThaumicAllAspect-verify.cfg}.
     * If the file does not exist, creates it with example entries and comments.
     * Each valid line specifies an item to check: {@code modid:itemName:meta=Display Name}.
     * Results are written to the scan log.
     * <p>
     * 从 {@code config/ThaumicAllAspect-verify.cfg} 读取用户自定义验证条目。
     * 如果文件不存在，会创建包含示例条目和注释的默认文件。
     * 每行有效条目指定一个要检查的物品：{@code 模组id:物品名:meta=显示名}。
     * 结果写入扫描日志。
     */
    private static void runVerifyFromConfig() {
        AspectScanner.ensureVerifyConfigExists();

        final List<String[]> entries = new ArrayList<>();
        try (final BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(AspectScanner.VERIFY_CONFIG), StandardCharsets.UTF_8))) {
            String line;
            while (null != (line = reader.readLine())) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Format: modid:itemName:meta=Display Name
                final int eqIdx = line.indexOf('=');
                final String key = 0 < eqIdx ? line.substring(0, eqIdx)
                    .trim() : line.trim();
                final String displayName = 0 < eqIdx ? line.substring(eqIdx + 1)
                    .trim() : key;

                final String[] parts = key.split(":");
                if (3 > parts.length) continue;
                final String registryName = parts[0] + ":" + parts[1];
                final String metaStr = parts[2];
                try {
                    final int meta = Integer.parseInt(metaStr);
                    entries.add(new String[] { registryName, String.valueOf(meta), displayName });
                } catch (final NumberFormatException ignored) {}
            }
        } catch (final FileNotFoundException e) {
            return;
        } catch (final IOException e) {
            ModFileLogger.warn("[ThaumicAllAspect] Error reading verify config: " + e.getMessage());
            return;
        }

        if (entries.isEmpty()) return;

        ModFileLogger.scanSummary("");
        ModFileLogger
            .scanSummary("========== " + tr("[Verify]") + " " + tr("User-defined verification") + " ==========");

        for (final String[] entry : entries) {
            AspectScanner.verifyItem(entry[0], Integer.parseInt(entry[1]), entry[2]);
        }
    }

    /**
     * Creates the verify config file with example entries if it does not exist.
     * 如果验证配置文件不存在，创建包含示例条目的默认文件。
     */
    private static void ensureVerifyConfigExists() {
        if (AspectScanner.VERIFY_CONFIG.exists()) return;
        final File dir = AspectScanner.VERIFY_CONFIG.getParentFile();
        if (null != dir && !dir.exists()) dir.mkdirs();
        try (final BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(AspectScanner.VERIFY_CONFIG), StandardCharsets.UTF_8))) {
            final String[] lines = { "# ============================================================",
                "# ThaumicAllAspect - Aspect Verification Config", "# ThaumicAllAspect - 要素验证配置文件",
                "# ============================================================", "#",
                "# This file lets you verify whether specific items received",
                "# the correct aspects after the full scan completes.",
                "# Results are written to: logs/ThaumicAllAspect-scan.log", "#", "# 此文件用于验证指定物品在完整扫描后是否获得了正确的要素。",
                "# 结果输出到: logs/ThaumicAllAspect-scan.log", "#",
                "# ============================================================", "# FORMAT / 格式",
                "# ============================================================", "#",
                "#   modid:itemName:meta=Display Name", "#",
                "#   modid      - The mod's ID (e.g. minecraft, Thaumcraft, IC2)",
                "#                模组ID（如 minecraft、Thaumcraft、IC2）",
                "#   itemName   - The item's registry name (NOT the display name)", "#                物品的注册名（不是显示名）",
                "#   meta       - The metadata/damage value (usually 0)", "#                元数据/损伤值（通常为 0）",
                "#   Display Name - Any label you want (just for readability)", "#                  任意标签（仅用于可读性）", "#",
                "# TIP: You can find registry names in the cache file:", "#   logs/ThaumicAllAspect-cache.cfg",
                "# Each line looks like: Thaumcraft:ItemResource@6 = vinculum=2, vitreus=2",
                "# The part before @ is modid:itemName, the part after @ is meta.", "#", "# 提示：你可以在缓存文件中找到注册名：",
                "#   logs/ThaumicAllAspect-cache.cfg", "# 每行格式如: Thaumcraft:ItemResource@6 = vinculum=2, vitreus=2",
                "# @ 前面是 模组id:物品名，@ 后面是 meta 值。", "#", "# Lines starting with # are comments (ignored).",
                "# 以 # 开头的行为注释（会被忽略）。", "# Remove the # at the beginning of a line to enable it.", "# 删除行首的 # 即可启用该条目。",
                "#", "# ============================================================", "# EXAMPLES / 示例",
                "# ============================================================", "#",
                "# --- Vanilla Minecraft / 原版 ---", "# minecraft:diamond:0=Diamond",
                "# minecraft:iron_ingot:0=Iron Ingot", "# minecraft:gold_ingot:0=Gold Ingot",
                "# minecraft:cobblestone:0=Cobblestone", "#", "# --- Thaumcraft / 神秘时代 ---",
                "# Thaumcraft:ItemResource:6=Amber", "# Thaumcraft:blockCosmeticOpaque:0=Amber Block",
                "# Thaumcraft:blockCosmeticOpaque:1=Amber Brick", "# Thaumcraft:ItemShard:0=Air Shard", "#",
                "# --- GregTech ---", "# gregtech:gt.metaitem.01:11300=Bronze Ingot", "#", "# --- IC2 ---",
                "# IC2:itemIngot:0=Copper Ingot", "#", "# --- Tinkers' Construct / 匠魂 ---",
                "# TConstruct:materials:0=Paper", "#", "# ============================================================",
                "# ADD YOUR ENTRIES BELOW / 在下方添加你的条目",
                "# ============================================================", "", };
            for (final String l : lines) {
                writer.write(l);
                writer.newLine();
            }
        } catch (final IOException e) {
            ModFileLogger.warn("[ThaumicAllAspect] Failed to create verify config: " + e.getMessage());
        }
    }

    /**
     * Logs the aspect state of a single item for verification.
     * Checks ThaumcraftApi.exists, getObjectAspects, and local CACHE.
     * <p>
     * 记录单个物品的要素状态以供验证。
     * 检查 ThaumcraftApi.exists、getObjectAspects 和本地 CACHE。
     */
    private static void verifyItem(final String registryName, final int meta, final String displayName) {
        final Item item = (Item) Item.itemRegistry.getObject(registryName);
        if (null == item) {
            ModFileLogger.scanSummary(
                tr("[Verify]") + " "
                    + displayName
                    + " ("
                    + registryName
                    + ":"
                    + meta
                    + ") - "
                    + tr("not found in registry!"));
            return;
        }
        final ItemStack stack = new ItemStack(item, 1, meta);
        final AspectList fromApi = ThaumcraftApiHelper.getObjectAspects(stack);
        final boolean apiHas = null != fromApi && 0 < fromApi.size();
        final boolean existsInMap = ThaumcraftApi.exists(item, meta);
        final String cacheKey = AspectUtils.key(stack);
        final AspectList cached = AspectUtils.CACHE.get(cacheKey);

        ModFileLogger.scanSummary(tr("[Verify]") + " " + displayName + " (" + registryName + ":" + meta + ")");
        ModFileLogger.scanSummary(tr("[Verify]") + "   ThaumcraftApi.exists = " + existsInMap);
        ModFileLogger.scanSummary(
            tr("[Verify]") + "   getObjectAspects = "
                + (apiHas ? AspectUtils.aspectListToString(fromApi) : tr("empty/null")));
        ModFileLogger.scanSummary(
            tr("[Verify]") + "   cache = "
                + (null != cached ? AspectUtils.aspectListToString(cached) : tr("not in cache")));
    }

    /**
     * Scans all registered fluids and assigns aspects to those missing them.
     * 扫描所有注册流体并为缺少要素的流体分配要素。
     *
     * <p>
     * Fluids are not part of {@code Item.itemRegistry}, so they require a dedicated scan pass
     * using {@code FluidRegistry.getRegisteredFluids()} to iterate all registered fluids.
     * </p>
     * <p>
     * 流体不属于 {@code Item.itemRegistry}，因此需要使用
     * {@code FluidRegistry.getRegisteredFluids()} 进行专门的扫描遍历所有已注册流体。
     * </p>
     *
     * <p>
     * For each fluid, finds a representative ItemStack (block form or filled container via
     * {@code AspectUtils.getFluidRepresentative}). Then tries two derivation strategies in order:
     * </p>
     * <p>
     * 对于每种流体，通过 {@code AspectUtils.getFluidRepresentative} 找到一个代表性 ItemStack
     * （方块形式或已装填容器）。然后依次尝试两种推导策略：
     * </p>
     * <ol>
     * <li>{@code deriveFluidFromMaterial} — smart derivation for molten metals/materials by looking up
     * the corresponding solid form in OreDict (e.g., "copper" fluid → "ingotCopper" → copper ingot aspects + AQUA)
     * 基于矿辞查找对应固体形式的熔融金属/材料智能推导（如 "copper" 流体 → "ingotCopper" → 铜锭要素 + AQUA）</li>
     * <li>Standard {@code getOrGenerateAspectsFor} derivation chain as fallback
     * 标准 {@code getOrGenerateAspectsFor} 推导链作为回退</li>
     * </ol>
     */
    private static void scanFluids() {
        int total = 0, assigned = 0;
        final Map<String, Fluid> registeredFluids = FluidRegistry.getRegisteredFluids();
        if (null == registeredFluids) return;
        for (final Map.Entry<String, Fluid> entry : registeredFluids.entrySet()) {
            final String name = entry.getKey();
            final Fluid fluid = entry.getValue();
            if ("air".equals(name) || null == fluid) continue;
            total++;

            try {
                final ItemStack rep = AspectUtils.getFluidRepresentative(fluid);
                if (null == rep) continue;
                if (AspectUtils.hasAspect(rep)) {
                    assigned++;
                    continue;
                }

                AspectList aspects = AspectDeriver.deriveFluidFromMaterial(name);

                if (null == aspects || 0 == aspects.size()) {
                    aspects = AspectDeriver.getOrGenerateAspectsFor(rep, 0, new HashSet<>());
                }

                if (null != aspects && 0 < aspects.size()) {
                    ThaumcraftApi.registerObjectTag(rep, aspects.copy());
                    assigned++;
                    ModFileLogger.scanSummary(
                        tr("[Fluid register]") + " fluid:" + name + " <- " + AspectUtils.aspectListToString(aspects));
                } else {
                    AspectUtils.FAILED_IDS.add("fluid:" + name);
                }
            } catch (final Exception e) {
                ModFileLogger.scanSummary(
                    tr("[Fluid crash]") + " fluid:"
                        + name
                        + " <- "
                        + e.getClass()
                            .getSimpleName()
                        + ": "
                        + e.getMessage());
                AspectUtils.FAILED_IDS.add("fluid:" + name);
            }
        }
        ModFileLogger.info(
            "[ThaumicAllAspect] " + tr("Fluid scan: total") + " " + total + ", " + tr("with aspects") + " " + assigned);
    }

    // ===== Index building methods / 索引构建方法 =====
    // These indexes are built once at startup and enable O(1) lookups during the derivation phase.
    // Without them, each item derivation would need to linearly scan all recipes — far too slow
    // for modpacks with tens of thousands of recipes.
    // 这些索引在启动时构建一次，使推导阶段能够 O(1) 查找。
    // 没有它们，每个物品的推导都需要线性扫描所有配方 —— 对于有数万配方的整合包来说太慢了。

    /**
     * Builds the OreDict metadata index: maps each {@code Item} to the set of metadata values
     * it is registered with in Forge's OreDictionary.
     * 构建矿辞元数据索引：将每个 {@code Item} 映射到它在 Forge OreDictionary 中注册的 metadata 值集合。
     *
     * <p>
     * This index is consumed by {@code AspectUtils.getMetasToScan()} to discover which
     * metadata values are meaningful for a given item. Many mod items register sub-types only
     * through OreDict (e.g., "dyeRed" → minecraft:dye meta 1), so without this index we'd
     * miss scannable variants and leave them without aspects.
     * </p>
     * <p>
     * 此索引被 {@code AspectUtils.getMetasToScan()} 使用，用于发现给定物品有哪些有意义的 metadata 值。
     * 许多模组物品仅通过矿辞注册子类型（如 "dyeRed" → minecraft:dye meta 1），
     * 因此没有此索引我们会遗漏可扫描的变体，导致它们没有要素。
     * </p>
     */
    private static void buildOreDictIndex() {
        final long t0 = System.currentTimeMillis();
        AspectUtils.ORE_DICT_METAS = new HashMap<>();
        for (final String oreName : OreDictionary.getOreNames()) {
            for (final ItemStack ore : OreDictionary.getOres(oreName)) {
                if (null == ore || null == ore.getItem()) continue;
                final int m = ore.getItemDamage();
                if (0 <= m && OreDictionary.WILDCARD_VALUE != m) {
                    Set<Integer> set = AspectUtils.ORE_DICT_METAS.get(ore.getItem());
                    if (null == set) {
                        set = new LinkedHashSet<>();
                        AspectUtils.ORE_DICT_METAS.put(ore.getItem(), set);
                    }
                    set.add(m);
                }
            }
        }
        ModFileLogger.info(
            tr("[Index]") + " "
                + tr("OreDictionary:")
                + " "
                + (System.currentTimeMillis() - t0)
                + " ms, "
                + AspectUtils.ORE_DICT_METAS.size()
                + " "
                + tr("items"));
    }

    /**
     * Builds the crafting recipe index: maps each output {@code Item} to its list of
     * {@code IRecipe} objects from the vanilla {@code CraftingManager}.
     * 构建合成配方索引：将每个输出 {@code Item} 映射到来自原版 {@code CraftingManager} 的
     * {@code IRecipe} 对象列表。
     *
     * <p>
     * This enables O(1) lookup of "what recipes produce this item?" during
     * {@code AspectDeriver.deriveFromRecipeIndex}. Also populates {@code RECIPE_OUTPUT_METAS}
     * with each output's metadata, which feeds into {@code getMetasToScan()} — if an item
     * appears as a recipe output at meta 3, we know meta 3 is a valid scannable variant.
     * </p>
     * <p>
     * 这使得 {@code AspectDeriver.deriveFromRecipeIndex} 中可以 O(1) 查找"哪些配方产出此物品？"。
     * 同时将每个输出的 metadata 填充到 {@code RECIPE_OUTPUT_METAS} 中，供 {@code getMetasToScan()} 使用 ——
     * 如果某物品作为配方输出出现在 meta 3，我们就知道 meta 3 是一个有效的可扫描变体。
     * </p>
     */
    private static void buildCraftingRecipeIndex() {
        final long t1 = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        final List<IRecipe> allRecipes = CraftingManager.getInstance()
            .getRecipeList();
        AspectUtils.RECIPE_INDEX = new HashMap<>();
        AspectUtils.RECIPE_OUTPUT_METAS = new HashMap<>();
        if (null == allRecipes) return;
        for (final IRecipe recipe : allRecipes) {
            if (null == recipe) continue;
            final ItemStack output;
            try {
                output = recipe.getRecipeOutput();
            } catch (final Exception e) {
                continue;
            }
            if (null == output || null == output.getItem()) continue;
            final Item outItem = output.getItem();
            final int outMeta = output.getItemDamage();

            List<IRecipe> list = AspectUtils.RECIPE_INDEX.get(outItem);
            if (null == list) {
                list = new ArrayList<>();
                AspectUtils.RECIPE_INDEX.put(outItem, list);
            }
            list.add(recipe);

            if (0 <= outMeta && OreDictionary.WILDCARD_VALUE != outMeta) {
                Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                if (null == ms) {
                    ms = new LinkedHashSet<>();
                    AspectUtils.RECIPE_OUTPUT_METAS.put(outItem, ms);
                }
                ms.add(outMeta);
            }
        }
        ModFileLogger.info(
            tr("[Index]") + " "
                + tr("Crafting recipes:")
                + " "
                + (System.currentTimeMillis() - t1)
                + " ms, "
                + allRecipes.size()
                + " "
                + tr("recipes,")
                + " "
                + AspectUtils.RECIPE_INDEX.size()
                + " "
                + tr("output items"));
    }

    /**
     * Builds the furnace smelting recipe index: maps each smelting output {@code Item} to its
     * list of input→output {@code Map.Entry} pairs from {@code FurnaceRecipes}.
     * 构建熔炉熔炼配方索引：将每个熔炼输出 {@code Item} 映射到来自 {@code FurnaceRecipes} 的
     * 输入→输出 {@code Map.Entry} 对列表。
     *
     * <p>
     * Functions identically to the crafting recipe index but for furnace recipes. Shares the
     * {@code RECIPE_OUTPUT_METAS} map with {@code buildCraftingRecipeIndex} — both contribute
     * output metadata to the same pool, so {@code getMetasToScan()} sees all recipe-discoverable variants.
     * </p>
     * <p>
     * 功能与合成配方索引相同，但用于熔炉配方。与 {@code buildCraftingRecipeIndex} 共享
     * {@code RECIPE_OUTPUT_METAS} 映射 —— 两者都向同一池中贡献输出 metadata，
     * 使 {@code getMetasToScan()} 能看到所有可通过配方发现的变体。
     * </p>
     */
    private static void buildFurnaceIndex() {
        final long t2 = System.currentTimeMillis();
        AspectUtils.FURNACE_INDEX = new HashMap<>();
        @SuppressWarnings("unchecked")
        final Map<ItemStack, ItemStack> smeltingMap = FurnaceRecipes.smelting()
            .getSmeltingList();
        if (null == smeltingMap) return;
        for (final Map.Entry<ItemStack, ItemStack> entry : smeltingMap.entrySet()) {
            final ItemStack output = entry.getValue();
            if (null == output || null == output.getItem()) continue;
            final Item outItem = output.getItem();
            final int outMeta = output.getItemDamage();

            List<Map.Entry<ItemStack, ItemStack>> fList = AspectUtils.FURNACE_INDEX.get(outItem);
            if (null == fList) {
                fList = new ArrayList<>();
                AspectUtils.FURNACE_INDEX.put(outItem, fList);
            }
            fList.add(entry);

            if (0 <= outMeta && OreDictionary.WILDCARD_VALUE != outMeta) {
                Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                if (null == ms) {
                    ms = new LinkedHashSet<>();
                    AspectUtils.RECIPE_OUTPUT_METAS.put(outItem, ms);
                }
                ms.add(outMeta);
            }
        }
        ModFileLogger.info(
            tr("[Index]") + " "
                + tr("Furnace recipes:")
                + " "
                + (System.currentTimeMillis() - t2)
                + " ms, "
                + smeltingMap.size()
                + " "
                + tr("entries,")
                + " "
                + AspectUtils.FURNACE_INDEX.size()
                + " "
                + tr("output items"));
    }

    /**
     * Builds the Thaumcraft recipe index: maps each output {@code Item} to its list of
     * TC-specific recipe objects ({@code IArcaneRecipe}, {@code InfusionRecipe}, {@code CrucibleRecipe}).
     * 构建神秘时代配方索引：将每个输出 {@code Item} 映射到 TC 专属配方对象列表
     * （{@code IArcaneRecipe}、{@code InfusionRecipe}、{@code CrucibleRecipe}）。
     *
     * <p>
     * Uses {@code ThaumcraftApi.getCraftingRecipes()} which returns a mixed {@code List<Object>}
     * containing all three TC recipe types. We instanceof-check each entry to extract its output
     * ItemStack. InfusionRecipe is special — its output can be an ItemStack or an Object (enchantment
     * recipes produce non-ItemStack results, which we skip).
     * </p>
     * <p>
     * 使用 {@code ThaumcraftApi.getCraftingRecipes()} 返回的混合 {@code List<Object>}，
     * 其中包含所有三种 TC 配方类型。我们对每个条目进行 instanceof 检查以提取其输出 ItemStack。
     * InfusionRecipe 比较特殊 —— 其输出可以是 ItemStack 或 Object（附魔配方产生非 ItemStack 结果，我们跳过这些）。
     * </p>
     */
    private static void buildTCRecipeIndex() {
        final long t3 = System.currentTimeMillis();
        AspectUtils.TC_RECIPE_INDEX = new HashMap<>();
        int tcRecipeCount = 0;
        @SuppressWarnings("unchecked")
        final List<Object> tcRecipes = ThaumcraftApi.getCraftingRecipes();
        if (null == tcRecipes) return;
        for (final Object obj : tcRecipes) {
            ItemStack tcOutput = null;

            if (obj instanceof IArcaneRecipe) {
                tcOutput = ((IArcaneRecipe) obj).getRecipeOutput();
            } else if (obj instanceof InfusionRecipe) {
                final Object infOut = ((InfusionRecipe) obj).getRecipeOutput();
                if (infOut instanceof ItemStack) tcOutput = (ItemStack) infOut;
            } else if (obj instanceof CrucibleRecipe) {
                tcOutput = ((CrucibleRecipe) obj).getRecipeOutput();
            }

            if (null == tcOutput || null == tcOutput.getItem()) continue;
            tcRecipeCount++;
            final Item outItem = tcOutput.getItem();
            final int outMeta = tcOutput.getItemDamage();

            List<Object> list = AspectUtils.TC_RECIPE_INDEX.get(outItem);
            if (null == list) {
                list = new ArrayList<>();
                AspectUtils.TC_RECIPE_INDEX.put(outItem, list);
            }
            list.add(obj);

            if (0 <= outMeta && OreDictionary.WILDCARD_VALUE != outMeta) {
                Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                if (null == ms) {
                    ms = new LinkedHashSet<>();
                    AspectUtils.RECIPE_OUTPUT_METAS.put(outItem, ms);
                }
                ms.add(outMeta);
            }
        }
        ModFileLogger.info(
            tr("[Index]") + " "
                + tr("Thaumcraft recipes:")
                + " "
                + (System.currentTimeMillis() - t3)
                + " ms, "
                + tcRecipeCount
                + " "
                + tr("entries,")
                + " "
                + AspectUtils.TC_RECIPE_INDEX.size()
                + " "
                + tr("output items"));
    }
}
