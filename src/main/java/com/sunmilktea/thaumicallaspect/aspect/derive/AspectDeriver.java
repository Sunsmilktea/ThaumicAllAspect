package com.sunmilktea.thaumicallaspect.aspect.derive;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.oredict.OreDictionary;

import com.sunmilktea.thaumicallaspect.ThaumicAllAspect;

import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.ThaumcraftApiHelper;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumcraft.api.crafting.ShapedArcaneRecipe;
import thaumcraft.api.crafting.ShapelessArcaneRecipe;

/**
 * Core derivation logic for aspect assignment. Contains the main dispatcher
 * and all recipe/inheritance-based derivation methods.
 * <p>
 * 要素推导核心逻辑。包含主调度器和所有基于配方/继承的推导方法。
 */
public class AspectDeriver {

    /**
     * Main derivation dispatcher. Attempts to find or generate aspects for the given ItemStack
     * using a prioritized chain of derivation strategies.
     * <p>
     * 主推导调度器。使用优先级链为给定的 ItemStack 查找或生成要素。
     * <p>
     * Priority / 优先级: TC registered -> OreDict -> Crafting -> Furnace -> TC recipes ->
     * Same-item meta inheritance -> TC generator (RECIPE_DECAY) -> Type-based (RECIPE_DECAY) ->
     * Special rules -> Keyword fallback
     */
    public static AspectList getOrGenerateAspectsFor(final ItemStack stack, final int depth,
        final Set<String> visiting) {
        if (null == stack || null == stack.getItem()) return null;

        // Redirect wildcard meta to meta 0 / 将通配符meta重定向到meta 0
        if (OreDictionary.WILDCARD_VALUE == stack.getItemDamage()) {
            return AspectDeriver.getOrGenerateAspectsFor(new ItemStack(stack.getItem(), 1, 0), depth, visiting);
        }

        final String k = AspectUtils.key(stack);

        // Check cache first / 先检查缓存
        if (AspectUtils.CACHE.containsKey(k)) {
            final AspectList cached = AspectUtils.CACHE.get(k);
            if (AspectUtils.hasPositiveAspectAmount(cached)) {
                if (0 == depth) AspectUtils.lastDerivePath = tr("cache hit");
                return AspectUtils.ensureMinOnePerAspect(cached)
                    .copy();
            }
        }

        // Cycle detection / 循环检测
        if (visiting.contains(k)) return null;
        visiting.add(k);

        AspectList result = null;

        try {
            String path = tr("unknown");

            // 1. Already registered in Thaumcraft / 已在TC中注册
            if (ThaumcraftApi.exists(stack.getItem(), stack.getItemDamage())) {
                final AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                if (AspectUtils.hasPositiveAspectAmount(existing)) {
                    result = AspectUtils.ensureMinOnePerAspect(existing)
                        .copy();
                    AspectUtils.CACHE.put(k, result.copy());
                    if (0 == depth) AspectUtils.lastDerivePath = tr("TC registered");
                    return result;
                }
            }

            // 2. Depth overflow safety / 深度溢出安全保护
            if (AspectUtils.MAX_RECIPE_DEPTH < depth) {
                result = AspectFallback.createGeneralFallback(stack);
                AspectUtils.CACHE.put(k, result.copy());
                if (0 == depth) AspectUtils.lastDerivePath = tr("depth overflow fallback");
                return result;
            }

            // 3. OreDictionary equivalents / 矿物辞典等价物
            result = AspectDeriver.deriveFromOreDictionary(stack, depth + 1, visiting);
            if (AspectUtils.hasPositiveAspectAmount(result)) {
                path = tr("OreDict equivalent");
            }

            // 4. Crafting recipes / 合成配方
            if (!AspectUtils.hasPositiveAspectAmount(result)) {
                result = AspectDeriver.deriveFromRecipeIndex(stack, depth + 1, visiting);
                if (AspectUtils.hasPositiveAspectAmount(result)) path = tr("crafting recipe");
            }

            // 5. Furnace recipes / 烧炼配方
            if (!AspectUtils.hasPositiveAspectAmount(result)) {
                result = AspectDeriver.deriveFromFurnace(stack, depth + 1, visiting);
                if (AspectUtils.hasPositiveAspectAmount(result)) path = tr("smelting recipe");
            }

            // 6. Thaumcraft recipes (arcane, infusion, crucible) / TC配方（奥术、注魔、坩埚）
            if (!AspectUtils.hasPositiveAspectAmount(result)) {
                result = AspectDeriver.deriveFromTCRecipes(stack, depth + 1, visiting);
                if (AspectUtils.hasPositiveAspectAmount(result)) path = tr("TC recipe");
            }

            // 7. Same-item metadata inheritance (non-recursive) / 同物品meta继承（非递归）
            if (!AspectUtils.hasPositiveAspectAmount(result)) {
                result = AspectDeriver.deriveFromSameItemMetas(stack, depth + 1, visiting);
                if (AspectUtils.hasPositiveAspectAmount(result)) path = tr("same-item meta inheritance");
            }

            // 8. TC internal tag generator with RECIPE_DECAY / TC内部生成器（RECIPE_DECAY 衰减）
            if (!AspectUtils.hasPositiveAspectAmount(result)) {
                final AspectList tcGen = AspectUtils.generateWithThaumcraft(stack);
                if (AspectUtils.hasPositiveAspectAmount(tcGen)) {
                    result = AspectUtils.scaleAspects(tcGen, AspectUtils.RECIPE_DECAY);
                    path = tr("TC generator");
                }
            }

            // 9. Type/material-based derivation with RECIPE_DECAY / 类型/材质推导（RECIPE_DECAY 衰减）
            if (!AspectUtils.hasPositiveAspectAmount(result)) {
                final AspectList typeAsp = AspectFallback.deriveFromType(stack);
                if (AspectUtils.hasPositiveAspectAmount(typeAsp)) {
                    result = AspectUtils.scaleAspects(typeAsp, AspectUtils.RECIPE_DECAY);
                    path = tr("type derivation");
                }
            }

            // 10. Special rules (food minimum, bauble minimum) / 特殊规则（食物下限、饰品下限）
            result = AspectFallback.applySpecialRules(stack, result);

            // 11. Keyword fallback as last resort / 关键词兜底（最后手段）
            if (null == result || 0 == result.size()) {
                result = AspectFallback.createGeneralFallback(stack);
                path = tr("keyword fallback");
            }

            if (0 == depth) AspectUtils.lastDerivePath = path;

            // If top-level result only has a single aspect type, optionally enrich it with keyword fallback aspects.
            // 仅在顶层调用且结果只有 1 种要素时（且配置允许），使用关键词兜底补充更多要素种类（不覆盖原有要素）。
            if (0 == depth && ThaumicAllAspect.enrichSingleAspect && AspectUtils.hasPositiveAspectAmount(result)) {
                Aspect[] baseAspects = result.getAspects();
                if (null == baseAspects) baseAspects = new Aspect[0];
                int nonZeroKinds = 0;
                for (final Aspect a : baseAspects) {
                    if (null != a && 0 < result.getAmount(a)) nonZeroKinds++;
                }
                if (1 >= nonZeroKinds) {
                    final AspectList fb = AspectFallback.createGeneralFallback(stack);
                    if (AspectUtils.hasPositiveAspectAmount(fb)) {
                        final AspectList scaledFb = AspectUtils.scaleAspects(fb, AspectUtils.RECIPE_DECAY);
                        final AspectList toMerge = AspectUtils.hasPositiveAspectAmount(scaledFb) ? scaledFb : fb;
                        Aspect[] extraAspects = toMerge.getAspects();
                        if (null == extraAspects) extraAspects = new Aspect[0];
                        for (final Aspect a : extraAspects) {
                            if (null == a) continue;
                            if (0 < result.getAmount(a)) continue; // keep existing aspect types untouched
                            final int amt = toMerge.getAmount(a);
                            if (0 < amt) result.add(a, amt);
                        }
                    }
                }
            }

            if (AspectUtils.hasPositiveAspectAmount(result)) {
                AspectUtils.CACHE.put(k, result.copy());
            }

            return result;
        } finally {
            visiting.remove(k);
        }
    }

    /**
     * Inherits aspects from other metadata variants of the same Item.
     * Only checks CACHE and ThaumcraftApi directly — never calls getOrGenerateAspectsFor
     * recursively to avoid circular dependency: e.g., amber block (meta 0) and amber brick
     * (meta 1) would recursively trigger each other's derivation, polluting the cache with
     * incomplete/empty results before either finishes.
     * <p>
     * 从同一物品的其他meta变体继承要素。
     * 仅直接检查缓存和TC API，绝不递归调用 getOrGenerateAspectsFor——
     * 避免循环依赖：例如琥珀方块(meta 0)和琥珀砖(meta 1)会互相触发推导，
     * 在两者完成之前就用不完整/空结果污染缓存。
     */
    private static AspectList deriveFromSameItemMetas(final ItemStack stack, final int depth,
        final Set<String> visiting) {
        final Item item = stack.getItem();
        final int myMeta = stack.getItemDamage();

        // Start with meta 0 as the first candidate: it's the most common default variant
        // and most likely to already have aspects registered or cached.
        // 首先加入 meta 0：它是最常见的默认变体，最可能已有要素注册或缓存。
        final Set<Integer> candidates = new LinkedHashSet<>();
        candidates.add(0);

        // Scan existing CACHE entries for the same item prefix (e.g. "minecraft:wool@")
        // to discover sibling metas that were already derived in earlier passes.
        // This avoids re-derivation and leverages work already done for other variants.
        // 扫描缓存中同一物品前缀的条目（如 "minecraft:wool@"），
        // 发现已在之前轮次中推导过的兄弟meta，避免重复推导。
        final Object nameObj = Item.itemRegistry.getNameForObject(item);
        if (null == nameObj) return null;
        final String itemName = nameObj.toString();
        final String prefix = itemName + "@";
        for (final String cacheKey : AspectUtils.CACHE.keySet()) {
            if (!cacheKey.startsWith(prefix)) continue;
            final String metaPart = cacheKey.substring(prefix.length());
            try {
                candidates.add(Integer.parseInt(metaPart));
            } catch (final NumberFormatException ignored) {}
        }

        // Also gather metas from creative tabs, OreDict registrations, and recipe outputs.
        // This catches metas that aren't in the cache yet but are known to exist in-game.
        // 还从创造标签页、矿辞注册和配方产出中收集meta，
        // 捕获尚未进入缓存但在游戏中确实存在的meta。
        try {
            final Set<Integer> scanMetas = AspectUtils.getMetasToScan(item);
            candidates.addAll(scanMetas);
        } catch (final Exception ignored) {}

        // Remove our own meta — we're looking for *other* variants to inherit from.
        // 移除自身meta——我们要从*其他*变体继承。
        candidates.remove(myMeta);

        // "Best score" selection: iterate all candidate metas and pick the sibling
        // with the richest aspect set (highest total aspect value). This heuristic
        // favors inheriting from the most "complete" variant rather than an arbitrary one.
        // "最高分"选择：遍历所有候选meta，选择要素集最丰富（总要素值最高）的兄弟。
        // 这个启发式倾向于从最"完整"的变体继承，而非任意选取。
        AspectList best = null;
        int bestScore = -1;

        for (final int otherMeta : candidates) {
            final ItemStack otherStack = new ItemStack(item, 1, otherMeta);
            final String otherKey = AspectUtils.key(otherStack);
            AspectList al = null;

            // Only check already-computed sources (CACHE and TC API) — no recursive derivation.
            // This is the key design decision to prevent circular dependency and cache pollution.
            // 只检查已计算的来源（缓存和TC API）——不递归推导。
            // 这是防止循环依赖和缓存污染的关键设计决策。
            if (AspectUtils.CACHE.containsKey(otherKey)) {
                final AspectList cached = AspectUtils.CACHE.get(otherKey);
                if (AspectUtils.hasPositiveAspectAmount(cached)) {
                    al = AspectUtils.ensureMinOnePerAspect(cached)
                        .copy();
                }
            }

            if (null == al && ThaumcraftApi.exists(otherStack.getItem(), otherMeta)) {
                final AspectList existing = ThaumcraftApiHelper.getObjectAspects(otherStack);
                if (AspectUtils.hasPositiveAspectAmount(existing)) {
                    al = AspectUtils.ensureMinOnePerAspect(existing)
                        .copy();
                }
            }

            if (AspectUtils.hasPositiveAspectAmount(al)) {
                final int score = AspectUtils.getAspectTotal(al);
                if (score > bestScore) {
                    bestScore = score;
                    best = al;
                }
            }
        }

        // Same-item meta variants (e.g., amber block vs amber brick) are the same material
        // in different shapes — they should have the SAME aspects without decay.
        // Unlike recipe/type/TC-generator derivation paths, no RECIPE_DECAY scaling is applied here.
        // 同物品meta变体（如琥珀方块 vs 琥珀砖块）是相同材料的不同形态——
        // 它们应该拥有相同的要素，不施加衰减。
        // 与配方/类型/TC生成器推导路径不同，此处不进行 RECIPE_DECAY 缩放。
        if (null != best) return best.copy();
        return null;
    }

    /**
     * Inherits aspects from OreDictionary equivalents of this ItemStack.
     * This enables cross-mod aspect inheritance: e.g., a GregTech iron ingot registered
     * as "ingotIron" will inherit aspects from the vanilla iron ingot via the same OreDict name.
     * Also works for JAOPCA and similar OreDict-heavy mods.
     * <p>
     * 从矿物辞典等价物继承要素。
     * 这实现了跨模组的要素继承：例如注册为 "ingotIron" 的GT铁锭
     * 可通过相同的矿辞名称从原版铁锭继承要素。也适用于JAOPCA等重度使用矿辞的模组。
     */
    private static AspectList deriveFromOreDictionary(final ItemStack stack, final int depth,
        final Set<String> visiting) {
        final int[] oreIds = OreDictionary.getOreIDs(stack);
        if (null == oreIds || 0 == oreIds.length) return null;

        for (final int oreId : oreIds) {
            final String oreName = OreDictionary.getOreName(oreId);
            // Skip "Unknown" — OreDictionary returns this for unregistered/invalid ore IDs.
            // 跳过 "Unknown"——OreDictionary 对未注册/无效的矿辞ID返回此值。
            if (null == oreName || oreName.isEmpty() || "Unknown".equals(oreName)) continue;

            final List<ItemStack> equivalents = OreDictionary.getOres(oreName);
            if (null == equivalents || equivalents.isEmpty()) continue;

            final List<ItemStack> others = new ArrayList<>();
            for (final ItemStack eq : equivalents) {
                if (null == eq || null == eq.getItem()) continue;
                // Exclude items with the same Item class to avoid self-referencing:
                // we want aspects from a *different* mod's equivalent, not ourselves.
                // 排除同一Item类的物品以避免自引用：
                // 我们需要的是*不同*模组的等价物的要素，而非自身。
                if (eq.getItem() == stack.getItem()) continue;

                // Normalize wildcard-meta OreDict entries to meta 0.
                // Some mods register with WILDCARD_VALUE to mean "any damage", but we need
                // a concrete meta to look up aspects.
                // 将通配符meta的矿辞条目规范化到meta 0。
                // 某些模组用 WILDCARD_VALUE 表示"任意损伤值"，但我们需要具体meta来查找要素。
                if (OreDictionary.WILDCARD_VALUE == eq.getItemDamage()) {
                    others.add(new ItemStack(eq.getItem(), 1, 0));
                } else {
                    others.add(eq);
                }
            }

            if (!others.isEmpty()) {
                final AspectList al = AspectUtils.getBestFromSlot(others, depth, visiting);
                if (AspectUtils.hasPositiveAspectAmount(al)) return al.copy();
            }
        }

        return null;
    }

    /**
     * Derives aspects from crafting recipes that produce this ItemStack.
     * Multiple recipes may produce the same item; we evaluate all of them and keep
     * the one yielding the highest total aspect score ("best recipe wins").
     * <p>
     * 从产出此物品的合成配方推导要素。
     * 同一物品可能有多个配方产出；我们评估所有配方并保留总要素分最高的那个（"最优配方胜出"）。
     */
    private static AspectList deriveFromRecipeIndex(final ItemStack target, final int depth,
        final Set<String> visiting) {
        final List<IRecipe> candidates = AspectUtils.RECIPE_INDEX.get(target.getItem());
        if (null == candidates || candidates.isEmpty()) return null;

        AspectList best = null;
        int bestScore = -1;

        // Iterate all recipes that produce this item (there may be multiple).
        // 遍历所有产出此物品的配方（可能有多个）。
        for (final IRecipe recipe : candidates) {
            final ItemStack output = recipe.getRecipeOutput();
            if (null == output || !AspectUtils.sameItem(output, target)) continue;

            final List<List<ItemStack>> inputs = AspectUtils.getRecipeInputs(recipe);
            if (inputs.isEmpty()) continue;

            // Sum up aspects from all ingredient slots of this recipe.
            // 汇总此配方所有材料槽的要素。
            final AspectList combined = new AspectList();
            boolean hasInput = false;

            for (final List<ItemStack> slot : inputs) {
                // Each slot may have multiple alternatives (OreDict substitutions);
                // getBestFromSlot picks the alternative with the highest aspect score.
                // 每个槽位可能有多个替代品（矿辞替换）；
                // getBestFromSlot 选择要素分最高的替代品。
                final AspectList slotAsp = AspectUtils.getBestFromSlot(slot, depth, visiting);
                if (AspectUtils.hasPositiveAspectAmount(slotAsp)) {
                    hasInput = true;
                    combined.add(slotAsp);
                }
            }

            if (hasInput && AspectUtils.hasPositiveAspectAmount(combined)) {
                // Scale combined ingredients by RECIPE_DECAY (90% decay, min 1 per aspect).
                // This prevents crafted items from having more aspects than raw materials combined.
                // 按 RECIPE_DECAY（衰减 90%，每种要素至少 1）缩放合并后的材料要素。
                // 防止合成品的要素超过原材料总和。
                final AspectList scaled = AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY);
                final int score = AspectUtils.getAspectTotal(scaled);
                // Keep the recipe that produces the highest total aspect score.
                // 保留产出最高总要素分的配方。
                if (score > bestScore) {
                    bestScore = score;
                    best = scaled;
                }
            }
        }

        return best;
    }

    /**
     * Derives aspects from furnace smelting recipes.
     * Furnace recipes are simpler than crafting: single input -> single output,
     * so we don't need "best of" selection — the first matching recipe wins.
     * Aspects are derived from the input item and scaled by RECIPE_DECAY (90% decay, min 1).
     * <p>
     * 从烧炼配方推导要素。
     * 熔炉配方比合成更简单：单一输入 -> 单一输出，
     * 因此不需要"最优"选择——第一个匹配的配方即胜出。
     * 要素来自输入物品并施加50%衰减。
     */
    private static AspectList deriveFromFurnace(final ItemStack target, final int depth, final Set<String> visiting) {
        if (null == AspectUtils.FURNACE_INDEX) return null;
        final List<Map.Entry<ItemStack, ItemStack>> candidates = AspectUtils.FURNACE_INDEX.get(target.getItem());
        if (null == candidates || candidates.isEmpty()) return null;

        for (final Map.Entry<ItemStack, ItemStack> entry : candidates) {
            final ItemStack output = entry.getValue();
            if (null == output || !AspectUtils.sameItem(output, target)) continue;

            final ItemStack input = entry.getKey();
            if (null == input || null == input.getItem()) continue;

            // Derive from input and apply RECIPE_DECAY; return immediately on first match.
            // 从输入推导并施加 RECIPE_DECAY；首次匹配即返回。
            final AspectList inputAsp = AspectDeriver.getOrGenerateAspectsFor(input, depth, visiting);
            if (AspectUtils.hasPositiveAspectAmount(inputAsp)) {
                return AspectUtils.scaleAspects(inputAsp, AspectUtils.RECIPE_DECAY);
            }
        }

        return null;
    }

    /**
     * Derives aspects from Thaumcraft recipes (arcane, infusion, crucible).
     * Handles 4 TC recipe types: ShapedArcaneRecipe, ShapelessArcaneRecipe,
     * InfusionRecipe, and CrucibleRecipe — each with a different input structure.
     * Uses the same "best recipe wins" strategy as crafting recipes.
     * <p>
     * 从TC配方推导要素（奥术合成、注魔、坩埚）。
     * 处理4种TC配方类型：有序奥术、无序奥术、注魔和坩埚——各有不同的输入结构。
     * 使用与合成配方相同的"最优配方胜出"策略。
     */
    private static AspectList deriveFromTCRecipes(final ItemStack target, final int depth, final Set<String> visiting) {
        if (null == AspectUtils.TC_RECIPE_INDEX) return null;
        final List<Object> candidates = AspectUtils.TC_RECIPE_INDEX.get(target.getItem());
        if (null == candidates || candidates.isEmpty()) return null;

        AspectList best = null;
        int bestScore = -1;

        for (final Object obj : candidates) {
            List<List<ItemStack>> inputs = null;
            ItemStack recipeOut = null;

            // ShapedArcaneRecipe / ShapelessArcaneRecipe: standard grid inputs, resolved via OreDict.
            // 有序/无序奥术配方：标准网格输入，通过矿辞解析。
            if (obj instanceof ShapedArcaneRecipe) {
                final ShapedArcaneRecipe sar = (ShapedArcaneRecipe) obj;
                recipeOut = sar.getRecipeOutput();
                inputs = new ArrayList<>();
                for (final Object o : sar.getInput()) {
                    inputs.add(AspectUtils.resolveOreInput(o));
                }
            } else if (obj instanceof ShapelessArcaneRecipe) {
                final ShapelessArcaneRecipe slr = (ShapelessArcaneRecipe) obj;
                recipeOut = slr.getRecipeOutput();
                inputs = new ArrayList<>();
                for (final Object o : slr.getInput()) {
                    inputs.add(AspectUtils.resolveOreInput(o));
                }
            } else if (obj instanceof InfusionRecipe) {
                final InfusionRecipe ir = (InfusionRecipe) obj;
                // InfusionRecipe has a special structure: center item + surrounding component array.
                // The output can be non-ItemStack (e.g., enchantment), so we must check.
                // 注魔配方有特殊结构：中心物品 + 周围组件数组。
                // 产出可能不是ItemStack（如附魔），因此必须检查。
                final Object irOut = ir.getRecipeOutput();
                if (!(irOut instanceof ItemStack)) continue;
                recipeOut = (ItemStack) irOut;
                inputs = new ArrayList<>();
                final ItemStack center = ir.getRecipeInput();
                if (null != center) inputs.add(Collections.singletonList(center.copy()));
                final ItemStack[] components = ir.getComponents();
                if (null != components) {
                    for (final ItemStack c : components) {
                        if (null != c) inputs.add(Collections.singletonList(c.copy()));
                    }
                }
            } else if (obj instanceof CrucibleRecipe) {
                final CrucibleRecipe cr = (CrucibleRecipe) obj;
                // CrucibleRecipe catalyst can be either a single ItemStack or an OreDict List<ItemStack>.
                // 坩埚配方的催化剂可以是单个ItemStack或矿辞的 List<ItemStack>。
                recipeOut = cr.getRecipeOutput();
                inputs = new ArrayList<>();
                final Object cat = cr.catalyst;
                if (cat instanceof ItemStack) {
                    inputs.add(Collections.singletonList(((ItemStack) cat).copy()));
                } else if (cat instanceof List) {
                    @SuppressWarnings("unchecked")
                    final List<ItemStack> oreList = (List<ItemStack>) cat;
                    inputs.add(new ArrayList<>(oreList));
                }
            } else {
                // Unknown TC recipe type: try reflection to extract output and inputs.
                // Some TC addons (Thaumic Tinkerer, Automagy, etc.) may define custom recipe classes.
                // 未知的 TC 配方类型：尝试反射提取输出和输入。
                // 部分 TC 附属（神秘匠魂、Automagy 等）可能定义了自定义配方类。
                try {
                    final Method outMethod = obj.getClass()
                        .getMethod("getRecipeOutput");
                    final Object outObj = outMethod.invoke(obj);
                    if (outObj instanceof ItemStack) {
                        recipeOut = (ItemStack) outObj;
                        // Reuse the generic reflection extractor for inputs
                        // 复用通用反射提取器来获取输入
                        if (obj instanceof IRecipe) {
                            inputs = AspectUtils.getRecipeInputs((IRecipe) obj);
                        }
                    }
                } catch (final Exception ignored) {}
            }

            if (null == recipeOut || !AspectUtils.sameItem(recipeOut, target) || null == inputs || inputs.isEmpty())
                continue;

            // Same aggregation logic as crafting recipes: sum all slots, scale, keep best.
            // 与合成配方相同的聚合逻辑：汇总所有槽位、缩放、保留最优。
            final AspectList combined = new AspectList();
            boolean hasInput = false;
            for (final List<ItemStack> slot : inputs) {
                final AspectList slotAsp = AspectUtils.getBestFromSlot(slot, depth, visiting);
                if (AspectUtils.hasPositiveAspectAmount(slotAsp)) {
                    hasInput = true;
                    combined.add(slotAsp);
                }
            }

            if (hasInput && AspectUtils.hasPositiveAspectAmount(combined)) {
                final AspectList scaled = AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY);
                final int score = AspectUtils.getAspectTotal(scaled);
                if (score > bestScore) {
                    bestScore = score;
                    best = scaled;
                }
            }
        }

        return best;
    }

    /**
     * Derives aspects for molten/liquid fluids by looking up their solid material equivalent
     * in the OreDictionary and adding ignis + aqua.
     * <p>
     * Name parsing strategy: tries multiple patterns to extract the material name from the
     * fluid registry name, because different mods use different naming conventions
     * (e.g., TiC uses "molten.iron", GT uses "moltenIron", some use "liquid_iron").
     * <p>
     * 通过在矿物辞典中查找对应固体材料并添加 ignis + aqua 来推导熔融/液态流体的要素。
     * <p>
     * 名称解析策略：尝试多种模式从流体注册名中提取材料名，因为不同模组使用不同的命名规范
     * （如TiC用 "molten.iron"，GT用 "moltenIron"，还有的用 "liquid_iron"）。
     */
    public static AspectList deriveFluidFromMaterial(final String fluidName) {
        final String lower = fluidName.toLowerCase();

        // Try multiple naming patterns: molten.X, moltenX, liquid_X, liquidX, X.molten, Xmolten
        // Order matters: more specific patterns (with separator) are tried before generic ones.
        // 尝试多种命名模式：molten.X, moltenX, liquid_X, liquidX, X.molten, Xmolten
        // 顺序重要：更具体的模式（带分隔符）优先于通用模式。
        String material = null;
        if (lower.startsWith("molten.")) material = lower.substring(7);
        else if (lower.startsWith("molten")) material = lower.substring(6);
        else if (lower.startsWith("liquid_")) material = lower.substring(7);
        else if (lower.startsWith("liquid")) material = lower.substring(6);
        else if (lower.endsWith(".molten")) material = lower.substring(0, lower.length() - 7);
        else if (lower.endsWith("molten")) material = lower.substring(0, lower.length() - 6);

        if (null != material && !material.isEmpty()) {
            // Clean separators and capitalize for OreDict lookup:
            // e.g., "pig_iron" -> "pigIron" -> "PigIron" for "ingotPigIron"
            // 清理分隔符并大写化以用于矿辞查找：
            // 如 "pig_iron" -> "pigIron" -> "PigIron" 对应 "ingotPigIron"
            material = material.replace("_", "")
                .replace(".", "");

            final String matCap = material.substring(0, 1)
                .toUpperCase() + material.substring(1);
            // Try OreDict prefixes in priority order: ingot first (most likely to have aspects
            // since TC registers aspects for ingots), then ore, dust, gem, block.
            // 按优先级顺序尝试矿辞前缀：锭优先（最可能有要素，因为TC为锭注册要素），
            // 然后是矿石、粉、宝石、方块。
            final String[] prefixes = { "ingot", "ore", "dust", "gem", "block" };
            for (final String pfx : prefixes) {
                final String oreName = pfx + matCap;
                final List<ItemStack> ores = OreDictionary.getOres(oreName);
                if (null != ores && !ores.isEmpty()) {
                    for (final ItemStack ore : ores) {
                        if (null == ore || null == ore.getItem()) continue;
                        final AspectList srcAsp = AspectDeriver.getOrGenerateAspectsFor(ore, 0, new HashSet<>());
                        if (AspectUtils.hasPositiveAspectAmount(srcAsp)) {
                            final AspectList result = srcAsp.copy();
                            // Add ignis (fire/heat from the melting process) and aqua (liquid state).
                            // These represent the physical transformation from solid to molten fluid.
                            // 添加 ignis（熔炼过程的火/热）和 aqua（液态）。
                            // 代表从固体到熔融流体的物理转变。
                            final Aspect ignis = AspectUtils.getAspect("ignis");
                            if (null != ignis) result.add(ignis, 2);
                            final Aspect aqua = AspectUtils.getAspect("aqua");
                            if (null != aqua) result.add(aqua, 1);
                            // Final RECIPE_DECAY applied to the whole result.
                            // 对整个结果施加 RECIPE_DECAY。
                            return AspectUtils.scaleAspects(result, AspectUtils.RECIPE_DECAY);
                        }
                    }
                }
            }
        }

        return null;
    }
}
