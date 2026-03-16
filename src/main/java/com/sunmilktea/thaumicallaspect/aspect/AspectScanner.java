package com.sunmilktea.thaumicallaspect.aspect;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

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

import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.ThaumcraftApiHelper;
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
public final class AspectScanner {

    private AspectScanner() {}

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

        long tGlobal = System.currentTimeMillis();
        ModFileLogger.info("========== [ThaumicAllAspect] " + tr("Starting full scan") + " ==========");
        ModFileLogger.beginScanLog();

        // Load aspect cache from config so server can have full aspects (e.g. Botania) without reflecting mods
        File configCache = new File("config", "ThaumicAllAspect-aspect-cache.cfg");
        if (configCache.isFile()) {
            int n = AspectUtils.loadAspectCacheFromFile(configCache);
            if (n > 0) ModFileLogger.info("[ThaumicAllAspect] Loaded " + n + " aspect entries from config cache.");
        }

        buildOreDictIndex();
        buildCraftingRecipeIndex();
        buildFurnaceIndex();
        buildTCRecipeIndex();

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
        Set<Item> allItems = new LinkedHashSet<>();
        TreeMap<String, List<Item>> modItemMap = new TreeMap<>();

        for (Object o : Item.itemRegistry) {
            Item item = (Item) o;
            if (item == null) continue;
            allItems.add(item);
        }
        for (Object o : Block.blockRegistry) {
            Block block = (Block) o;
            if (block == null || block == Blocks.air) continue;
            Item item = Item.getItemFromBlock(block);
            if (item != null) allItems.add(item);
        }

        for (Item item : allItems) {
            Object nameObj = Item.itemRegistry.getNameForObject(item);
            if (nameObj == null) continue;
            String regName = nameObj.toString();
            String modId = regName.contains(":") ? regName.substring(0, regName.indexOf(':')) : "minecraft";
            List<Item> list = modItemMap.get(modId);
            if (list == null) {
                list = new ArrayList<>();
                modItemMap.put(modId, list);
            }
            list.add(item);
        }

        int totalItems = allItems.size();
        int totalMods = modItemMap.size();
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

        // --- Phase 3: Pass 1 scan — iterate all items, derive aspects ---
        // --- 第 3 阶段：第一轮扫描 — 遍历所有物品，推导要素 ---
        //
        // We do NOT skip any mod: vanilla (minecraft) and every other mod are scanned the same way.
        // 不按模组跳过：原版（minecraft）与所有模组一视同仁，全部参与扫描。
        //
        // For each item, we first discover all valid metadata values via getMetasToScan(), which
        // unions metadata from: creative tabs, OreDict registrations, recipe outputs, and a
        // fallback range of 0–15. This ensures we don't miss sub-items (e.g., wood planks meta 0–5).
        // 对于每个物品，我们首先通过 getMetasToScan() 发现所有有效的 metadata 值，
        // 它合并了来自创造模式标签页、矿辞注册、配方输出和 0–15 回退范围的 metadata。
        // 这确保我们不会遗漏子物品（如木板 meta 0–5）。
        //
        // The ONLY skip is: Thaumcraft has already registered non-empty aspects for that stack.
        // 唯一的跳过条件：该物品在神秘时代中已注册且要素列表非空。
        //
        // Items that already have TC aspects (checked by hasAspect) are SKIPPED to avoid overwriting
        // hand-authored or mod-provided aspect assignments. This is a critical design choice:
        // we only fill gaps, never clobber existing data.
        // 已有 TC 要素的物品（通过 hasAspect 检查）会被跳过，以避免覆盖手动编写或模组提供的要素分配。
        // 这是一个关键设计决策：我们只填补空白，绝不覆盖已有数据。
        //
        // Each successful derivation is IMMEDIATELY registered via ThaumcraftApi.registerObjectTag,
        // so that subsequent items in the same pass can reference newly derived aspects in their
        // recipe-based derivation (forward dependency resolution within a single pass).
        // 每次成功推导后立即通过 ThaumcraftApi.registerObjectTag 注册，
        // 这样同一轮中后续物品可以在基于配方的推导中引用新推导出的要素（单轮内的前向依赖解析）。
        //
        // Items that fail derivation (null or empty AspectList) are collected into pass1Failed
        // for the multi-pass retry phase, where they get another chance after more aspects
        // become available in the system.
        // 推导失败（null 或空 AspectList）的物品被收集到 pass1Failed 中，
        // 留给多轮重试阶段，在系统中有更多要素可用后它们会获得再次机会。
        List<ItemStack> pass1Failed = new ArrayList<>();
        int modIndex = 0;
        for (Map.Entry<String, List<Item>> modEntry : modItemMap.entrySet()) {
            modIndex++;
            String modId = modEntry.getKey();
            List<Item> items = modEntry.getValue();
            int modReg = 0, modSkip = 0, modFail = 0;

            for (Item item : items) {
                String id;
                try {
                    id = Item.itemRegistry.getNameForObject(item)
                        .toString();
                } catch (Exception e) {
                    continue;
                }
                boolean hasAny = false;

                Set<Integer> metas;
                try {
                    metas = AspectUtils.getMetasToScan(item);
                } catch (Exception e) {
                    ModFileLogger
                        .scan(tr("[Error]") + " " + id + " " + tr("failed to get metas:") + " " + e.getMessage());
                    continue;
                }

                for (int meta : metas) {
                    try {
                        ItemStack stack = new ItemStack(item, 1, meta);

                        // Check TC registration and pre-populate CACHE in one step
                        // (avoids calling getObjectAspects twice — once in hasAspect, once for cache).
                        // 一步完成 TC 注册检查和缓存预填充
                        // （避免调用两次 getObjectAspects——hasAspect 中一次，缓存一次）。
                        if (ThaumcraftApi.exists(stack.getItem(), stack.getItemDamage())) {
                            AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                            if (existing != null && existing.size() > 0) {
                                AspectUtils.CACHE.put(AspectUtils.key(stack), existing.copy());
                                hasAny = true;
                                AspectUtils.statAlreadyHad++;
                                modSkip++;
                                continue;
                            }
                        }

                        AspectUtils.lastDerivePath = "";
                        AspectList aspects = AspectDeriver.getOrGenerateAspectsFor(stack, 0, new HashSet<String>());
                        if (aspects != null && aspects.size() > 0) {
                            String aspectStr = AspectUtils.aspectListToString(aspects);
                            String displayName;
                            try {
                                displayName = stack.getDisplayName();
                            } catch (Exception e) {
                                displayName = "?";
                            }
                            ThaumcraftApi.registerObjectTag(stack, aspects.copy());
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
                    } catch (Exception e) {
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

            String modSummary = tr("[Scan]") + " ("
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
        // Maximum 5 retry passes handles deeply nested dependency chains ("套娃配方").
        // 如果某轮未产生新注册则提前停止 —— 这意味着已到达不动点，无法取得更多进展（剩余物品确实无法推导）。
        // 最多 5 轮重试可处理深层嵌套的依赖链（"套娃配方"）。
        int maxRetryPasses = 5;
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

            List<ItemStack> stillFailed = new ArrayList<>();
            int passReg = 0, passFail = 0;

            for (ItemStack stack : retryList) {
                String id;
                try {
                    id = Item.itemRegistry.getNameForObject(stack.getItem())
                        .toString();
                } catch (Exception e) {
                    continue;
                }
                int meta = stack.getItemDamage();

                try {
                    // Re-check: item may have been registered by a previous pass
                    // 重新检查：物品可能已在之前的轮次中被注册
                    if (ThaumcraftApi.exists(stack.getItem(), stack.getItemDamage())) {
                        AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                        if (existing != null && existing.size() > 0) {
                            AspectUtils.CACHE.put(AspectUtils.key(stack), existing.copy());
                            passReg++;
                            AspectUtils.statAlreadyHad++;
                            AspectUtils.FAILED_IDS.remove(id);
                            continue;
                        }
                    }

                    AspectUtils.lastDerivePath = "";
                    AspectList aspects = AspectDeriver.getOrGenerateAspectsFor(stack, 0, new HashSet<String>());
                    if (aspects != null && aspects.size() > 0) {
                        String aspectStr = AspectUtils.aspectListToString(aspects);
                        String displayName;
                        try {
                            displayName = stack.getDisplayName();
                        } catch (Exception e) {
                            displayName = "?";
                        }
                        ThaumcraftApi.registerObjectTag(stack, aspects.copy());
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
                } catch (Exception e) {
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

            String passSummary = tr("[Pass") + " "
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

            if (passReg == 0) {
                String stopMsg = tr("[Retry]") + " " + tr("No new registrations this pass, stopping");
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
        scanFluids();

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
            Iterator<String> failIter = AspectUtils.FAILED_IDS.iterator();
            while (failIter.hasNext()) {
                String failedId = failIter.next();
                try {
                    if (failedId.startsWith("fluid:")) continue;
                    Object itemObj = Item.itemRegistry.getObject(failedId);
                    if (!(itemObj instanceof Item)) continue;
                    Item item = (Item) itemObj;
                    boolean found = false;
                    for (int meta = 0; meta <= 15; meta++) {
                        ItemStack stack = new ItemStack(item, 1, meta);
                        if (AspectUtils.hasAspect(stack)) {
                            AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                            if (existing != null && existing.size() > 0) {
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
                } catch (Exception ignored) {}
            }
            if (recovered > 0) {
                String msg = tr("[Post-scan]") + " "
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
            Map<String, AspectList> bestByItem = new HashMap<>();

            for (Map.Entry<String, AspectList> entry : AspectUtils.CACHE.entrySet()) {
                String key = entry.getKey();
                int atIdx = key.indexOf('@');
                if (atIdx < 0) continue;
                String baseName = key.substring(0, atIdx);
                AspectList al = entry.getValue();
                if (al == null || al.size() == 0) continue;
                int score = AspectUtils.getAspectTotal(al);

                AspectList existing = bestByItem.get(baseName);
                if (existing == null || AspectUtils.getAspectTotal(existing) < score) {
                    bestByItem.put(baseName, al.copy());
                }
            }

            // Iterate only over base names that have a donor (from CACHE). Same set of items get
            // donor applied as before (previously we skipped when donor==null); no behavior change.
            // 仅遍历有供体的 baseName，与原先“仅对 donor 非空时处理”等价，不改变功能。
            for (Map.Entry<String, AspectList> entry : bestByItem.entrySet()) {
                String name = entry.getKey();
                AspectList donor = entry.getValue();
                if (donor == null || donor.size() == 0) continue;

                Object itemObj = Item.itemRegistry.getObject(name);
                if (!(itemObj instanceof Item)) continue;
                Item item = (Item) itemObj;

                Set<Integer> metas;
                try {
                    metas = AspectUtils.getMetasToScan(item);
                } catch (Exception e) {
                    metas = new HashSet<>();
                    metas.add(0);
                }

                for (int meta : metas) {
                    ItemStack stack = new ItemStack(item, 1, meta);
                    if (AspectUtils.hasAspect(stack)) continue;

                    ThaumcraftApi.registerObjectTag(stack, donor.copy());
                    AspectUtils.CACHE.put(AspectUtils.key(stack), donor.copy());
                    metaFixed++;
                }
            }

            if (metaFixed > 0) {
                String msg = tr("[Post-scan]") + " "
                    + tr("Meta inheritance sweep: fixed")
                    + " "
                    + metaFixed
                    + " "
                    + tr("item metas");
                ModFileLogger.info(msg);
                ModFileLogger.scanSummary(msg);
            }
        }

        // Summary statistics / 统计总结
        String[] stats = { "", tr("[Stats]") + " ===== " + tr("Full scan summary") + " =====", tr(
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
        for (String s : stats) {
            ModFileLogger.info(s);
            ModFileLogger.scanSummary(s);
        }

        if (!AspectUtils.FAILED_IDS.isEmpty()) {
            ModFileLogger.warn("[ThaumicAllAspect] " + tr("The following items/blocks/fluids still have no aspects:"));
            ModFileLogger.scanSummary("");
            ModFileLogger
                .scanSummary(tr("[Failures]") + " " + tr("The following items/blocks/fluids still have no aspects:"));
            for (String id : AspectUtils.FAILED_IDS) {
                ModFileLogger.warn(" - " + id);
                ModFileLogger.scanSummary(" - " + id);
            }
            ModFileLogger.appendFailureIds(
                "[ThaumicAllAspect] " + tr("Failed item/block/fluid scan IDs:"),
                AspectUtils.FAILED_IDS);
        }

        // Dump aspect cache to file / 导出要素缓存文件
        ModFileLogger.writeCacheFile(AspectUtils.CACHE);
        ModFileLogger.writeCacheFile(AspectUtils.CACHE, new File("config", "ThaumicAllAspect-aspect-cache.cfg"), false);

        // User-defined verification from config / 用户自定义验证（来自配置文件）
        runVerifyFromConfig();

        long totalMs = System.currentTimeMillis() - tGlobal;
        String doneMsg = "========== [ThaumicAllAspect] " + tr("Full scan complete, total time")
            + " "
            + totalMs
            + " ms ==========";
        ModFileLogger.info(doneMsg);
        ModFileLogger.scanSummary(doneMsg);
        ModFileLogger.endScanLog();
    }

    // ==================== User-defined Verification / 用户自定义验证 ====================

    private static final File VERIFY_CONFIG = new File("config", "ThaumicAllAspect-verify.cfg");

    /**
     * Reads user-defined verification entries from {@code config/ThaumicAllAspect-verify.cfg}.
     * If the file does not exist, creates it with example entries and comments.
     * Each valid line specifies an item to check: {@code modid:itemName:meta=Display Name}.
     * Results are written to the scan log.
     *
     * 从 {@code config/ThaumicAllAspect-verify.cfg} 读取用户自定义验证条目。
     * 如果文件不存在，会创建包含示例条目和注释的默认文件。
     * 每行有效条目指定一个要检查的物品：{@code 模组id:物品名:meta=显示名}。
     * 结果写入扫描日志。
     */
    private static void runVerifyFromConfig() {
        ensureVerifyConfigExists();

        List<String[]> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(VERIFY_CONFIG), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Format: modid:itemName:meta=Display Name
                int eqIdx = line.indexOf('=');
                String key = eqIdx > 0 ? line.substring(0, eqIdx)
                    .trim() : line.trim();
                String displayName = eqIdx > 0 ? line.substring(eqIdx + 1)
                    .trim() : key;

                String[] parts = key.split(":");
                if (parts.length < 3) continue;
                String registryName = parts[0] + ":" + parts[1];
                String metaStr = parts[2];
                try {
                    int meta = Integer.parseInt(metaStr);
                    entries.add(new String[] { registryName, String.valueOf(meta), displayName });
                } catch (NumberFormatException ignored) {}
            }
        } catch (FileNotFoundException e) {
            return;
        } catch (IOException e) {
            ModFileLogger.warn("[ThaumicAllAspect] Error reading verify config: " + e.getMessage());
            return;
        }

        if (entries.isEmpty()) return;

        ModFileLogger.scanSummary("");
        ModFileLogger
            .scanSummary("========== " + tr("[Verify]") + " " + tr("User-defined verification") + " ==========");

        for (String[] entry : entries) {
            verifyItem(entry[0], Integer.parseInt(entry[1]), entry[2]);
        }
    }

    /**
     * Creates the verify config file with example entries if it does not exist.
     * 如果验证配置文件不存在，创建包含示例条目的默认文件。
     */
    private static void ensureVerifyConfigExists() {
        if (VERIFY_CONFIG.exists()) return;
        File dir = VERIFY_CONFIG.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(VERIFY_CONFIG), StandardCharsets.UTF_8))) {
            String[] lines = { "# ============================================================",
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
            for (String l : lines) {
                writer.write(l);
                writer.newLine();
            }
        } catch (IOException e) {
            ModFileLogger.warn("[ThaumicAllAspect] Failed to create verify config: " + e.getMessage());
        }
    }

    /**
     * Logs the aspect state of a single item for verification.
     * Checks ThaumcraftApi.exists, getObjectAspects, and local CACHE.
     *
     * 记录单个物品的要素状态以供验证。
     * 检查 ThaumcraftApi.exists、getObjectAspects 和本地 CACHE。
     */
    private static void verifyItem(String registryName, int meta, String displayName) {
        Item item = (Item) Item.itemRegistry.getObject(registryName);
        if (item == null) {
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
        ItemStack stack = new ItemStack(item, 1, meta);
        AspectList fromApi = ThaumcraftApiHelper.getObjectAspects(stack);
        boolean apiHas = fromApi != null && fromApi.size() > 0;
        boolean existsInMap = ThaumcraftApi.exists(item, meta);
        String cacheKey = AspectUtils.key(stack);
        AspectList cached = AspectUtils.CACHE.get(cacheKey);

        ModFileLogger.scanSummary(tr("[Verify]") + " " + displayName + " (" + registryName + ":" + meta + ")");
        ModFileLogger.scanSummary(tr("[Verify]") + "   ThaumcraftApi.exists = " + existsInMap);
        ModFileLogger.scanSummary(
            tr("[Verify]") + "   getObjectAspects = "
                + (apiHas ? AspectUtils.aspectListToString(fromApi) : tr("empty/null")));
        ModFileLogger.scanSummary(
            tr("[Verify]") + "   cache = "
                + (cached != null ? AspectUtils.aspectListToString(cached) : tr("not in cache")));
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
        Map<String, Fluid> registeredFluids = FluidRegistry.getRegisteredFluids();
        if (registeredFluids == null) return;
        for (Map.Entry<String, Fluid> entry : registeredFluids.entrySet()) {
            String name = entry.getKey();
            Fluid fluid = entry.getValue();
            if ("air".equals(name) || fluid == null) continue;
            total++;

            try {
                ItemStack rep = AspectUtils.getFluidRepresentative(fluid);
                if (rep == null) continue;
                if (AspectUtils.hasAspect(rep)) {
                    assigned++;
                    continue;
                }

                AspectList aspects = AspectDeriver.deriveFluidFromMaterial(name);

                if (aspects == null || aspects.size() == 0) {
                    aspects = AspectDeriver.getOrGenerateAspectsFor(rep, 0, new HashSet<>());
                }

                if (aspects != null && aspects.size() > 0) {
                    ThaumcraftApi.registerObjectTag(rep, aspects.copy());
                    assigned++;
                    ModFileLogger.scanSummary(
                        tr("[Fluid register]") + " fluid:" + name + " <- " + AspectUtils.aspectListToString(aspects));
                } else {
                    AspectUtils.FAILED_IDS.add("fluid:" + name);
                }
            } catch (Exception e) {
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
        long t0 = System.currentTimeMillis();
        AspectUtils.ORE_DICT_METAS = new HashMap<>();
        for (String oreName : OreDictionary.getOreNames()) {
            for (ItemStack ore : OreDictionary.getOres(oreName)) {
                if (ore == null || ore.getItem() == null) continue;
                int m = ore.getItemDamage();
                if (m >= 0 && m != OreDictionary.WILDCARD_VALUE) {
                    Set<Integer> set = AspectUtils.ORE_DICT_METAS.get(ore.getItem());
                    if (set == null) {
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
        long t1 = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<IRecipe> allRecipes = CraftingManager.getInstance()
            .getRecipeList();
        AspectUtils.RECIPE_INDEX = new HashMap<>();
        AspectUtils.RECIPE_OUTPUT_METAS = new HashMap<>();
        if (allRecipes == null) return;
        for (IRecipe recipe : allRecipes) {
            if (recipe == null) continue;
            ItemStack output;
            try {
                output = recipe.getRecipeOutput();
            } catch (Exception e) {
                continue;
            }
            if (output == null || output.getItem() == null) continue;
            Item outItem = output.getItem();
            int outMeta = output.getItemDamage();

            List<IRecipe> list = AspectUtils.RECIPE_INDEX.get(outItem);
            if (list == null) {
                list = new ArrayList<>();
                AspectUtils.RECIPE_INDEX.put(outItem, list);
            }
            list.add(recipe);

            if (outMeta >= 0 && outMeta != OreDictionary.WILDCARD_VALUE) {
                Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                if (ms == null) {
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
        long t2 = System.currentTimeMillis();
        AspectUtils.FURNACE_INDEX = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<ItemStack, ItemStack> smeltingMap = FurnaceRecipes.smelting()
            .getSmeltingList();
        if (smeltingMap == null) return;
        for (Map.Entry<ItemStack, ItemStack> entry : smeltingMap.entrySet()) {
            ItemStack output = entry.getValue();
            if (output == null || output.getItem() == null) continue;
            Item outItem = output.getItem();
            int outMeta = output.getItemDamage();

            List<Map.Entry<ItemStack, ItemStack>> fList = AspectUtils.FURNACE_INDEX.get(outItem);
            if (fList == null) {
                fList = new ArrayList<>();
                AspectUtils.FURNACE_INDEX.put(outItem, fList);
            }
            fList.add(entry);

            if (outMeta >= 0 && outMeta != OreDictionary.WILDCARD_VALUE) {
                Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                if (ms == null) {
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
        long t3 = System.currentTimeMillis();
        AspectUtils.TC_RECIPE_INDEX = new HashMap<>();
        int tcRecipeCount = 0;
        @SuppressWarnings("unchecked")
        List<Object> tcRecipes = ThaumcraftApi.getCraftingRecipes();
        if (tcRecipes == null) return;
        for (Object obj : tcRecipes) {
            ItemStack tcOutput = null;

            if (obj instanceof IArcaneRecipe) {
                tcOutput = ((IArcaneRecipe) obj).getRecipeOutput();
            } else if (obj instanceof InfusionRecipe) {
                Object infOut = ((InfusionRecipe) obj).getRecipeOutput();
                if (infOut instanceof ItemStack) tcOutput = (ItemStack) infOut;
            } else if (obj instanceof CrucibleRecipe) {
                tcOutput = ((CrucibleRecipe) obj).getRecipeOutput();
            }

            if (tcOutput == null || tcOutput.getItem() == null) continue;
            tcRecipeCount++;
            Item outItem = tcOutput.getItem();
            int outMeta = tcOutput.getItemDamage();

            List<Object> list = AspectUtils.TC_RECIPE_INDEX.get(outItem);
            if (list == null) {
                list = new ArrayList<>();
                AspectUtils.TC_RECIPE_INDEX.put(outItem, list);
            }
            list.add(obj);

            if (outMeta >= 0 && outMeta != OreDictionary.WILDCARD_VALUE) {
                Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                if (ms == null) {
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
