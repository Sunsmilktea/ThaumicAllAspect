package com.sunmilktea.thaumicallaspect.aspect;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.lang.reflect.Method;
import java.util.*;

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
 *
 * 要素推导核心逻辑。包含主调度器和所有基于配方/继承的推导方法。
 */
public final class AspectDeriver {

    private AspectDeriver() {}

    /**
     * Main derivation dispatcher. Attempts to find or generate aspects for the given ItemStack
     * using a prioritized chain of derivation strategies.
     *
     * 主推导调度器。使用优先级链为给定的 ItemStack 查找或生成要素。
     *
     * Priority / 优先级: TC registered -> OreDict -> Crafting -> Furnace -> TC recipes ->
     * Same-item meta inheritance -> TC generator (RECIPE_DECAY) -> Type-based (RECIPE_DECAY) ->
     * Special rules -> Keyword fallback
     */
    public static AspectList getOrGenerateAspectsFor(ItemStack stack, int depth, Set<String> visiting) {
        if (stack == null || stack.getItem() == null) return null;

        // Redirect wildcard meta to meta 0 / 将通配符meta重定向到meta 0
        if (stack.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
            return getOrGenerateAspectsFor(new ItemStack(stack.getItem(), 1, 0), depth, visiting);
        }

        String k = AspectUtils.key(stack);

        // Check cache first / 先检查缓存
        if (AspectUtils.CACHE.containsKey(k)) {
            AspectList cached = AspectUtils.CACHE.get(k);
            if (cached != null && cached.size() > 0) {
                if (depth == 0) AspectUtils.lastDerivePath = tr("cache hit");
                return cached.copy();
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
                AspectList existing = ThaumcraftApiHelper.getObjectAspects(stack);
                if (existing != null && existing.size() > 0) {
                    result = existing.copy();
                    AspectUtils.CACHE.put(k, result.copy());
                    if (depth == 0) AspectUtils.lastDerivePath = tr("TC registered");
                    return result;
                }
            }

            // 2. Depth overflow safety / 深度溢出安全保护
            if (depth > AspectUtils.MAX_RECIPE_DEPTH) {
                result = AspectFallback.createGeneralFallback(stack);
                AspectUtils.CACHE.put(k, result.copy());
                if (depth == 0) AspectUtils.lastDerivePath = tr("depth overflow fallback");
                return result;
            }

            // 3. OreDictionary equivalents / 矿物辞典等价物
            result = deriveFromOreDictionary(stack, depth + 1, visiting);
            if (result != null && result.size() > 0) {
                path = tr("OreDict equivalent");
            }

            // 4. Crafting recipes / 合成配方
            if (result == null || result.size() == 0) {
                result = deriveFromRecipeIndex(stack, depth + 1, visiting);
                if (result != null && result.size() > 0) path = tr("crafting recipe");
            }

            // 5. Furnace recipes / 烧炼配方
            if (result == null || result.size() == 0) {
                result = deriveFromFurnace(stack, depth + 1, visiting);
                if (result != null && result.size() > 0) path = tr("smelting recipe");
            }

            // 6. Thaumcraft recipes (arcane, infusion, crucible) / TC配方（奥术、注魔、坩埚）
            if (result == null || result.size() == 0) {
                result = deriveFromTCRecipes(stack, depth + 1, visiting);
                if (result != null && result.size() > 0) path = tr("TC recipe");
            }

            // 7. Same-item metadata inheritance (non-recursive) / 同物品meta继承（非递归）
            if (result == null || result.size() == 0) {
                result = deriveFromSameItemMetas(stack, depth + 1, visiting);
                if (result != null && result.size() > 0) path = tr("same-item meta inheritance");
            }

            // 8. TC internal tag generator with RECIPE_DECAY / TC内部生成器（RECIPE_DECAY 衰减）
            if (result == null || result.size() == 0) {
                AspectList tcGen = AspectUtils.generateWithThaumcraft(stack);
                if (tcGen != null && tcGen.size() > 0) {
                    result = AspectUtils.scaleAspects(tcGen, AspectUtils.RECIPE_DECAY);
                    path = tr("TC generator");
                }
            }

            // 9. Type/material-based derivation with RECIPE_DECAY / 类型/材质推导（RECIPE_DECAY 衰减）
            if (result == null || result.size() == 0) {
                AspectList typeAsp = AspectFallback.deriveFromType(stack);
                if (typeAsp != null && typeAsp.size() > 0) {
                    result = AspectUtils.scaleAspects(typeAsp, AspectUtils.RECIPE_DECAY);
                    path = tr("type derivation");
                }
            }

            // 10. Special rules (food minimum, bauble minimum) / 特殊规则（食物下限、饰品下限）
            result = AspectFallback.applySpecialRules(stack, result);

            // 11. Keyword fallback as last resort / 关键词兜底（最后手段）
            if (result == null || result.size() == 0) {
                result = AspectFallback.createGeneralFallback(stack);
                path = tr("keyword fallback");
            }

            if (depth == 0) AspectUtils.lastDerivePath = path;

            // If top-level result only has a single aspect type, optionally enrich it with keyword fallback aspects.
            // 仅在顶层调用且结果只有 1 种要素时（且配置允许），使用关键词兜底补充更多要素种类（不覆盖原有要素）。
            if (depth == 0 && ThaumicAllAspect.enrichSingleAspect && result != null && result.size() > 0) {
                Aspect[] baseAspects = result.getAspects();
                if (baseAspects == null) baseAspects = new Aspect[0];
                int nonZeroKinds = 0;
                for (Aspect a : baseAspects) {
                    if (a != null && result.getAmount(a) > 0) nonZeroKinds++;
                }
                if (nonZeroKinds <= 1) {
                    AspectList fb = AspectFallback.createGeneralFallback(stack);
                    if (fb != null && fb.size() > 0) {
                        AspectList scaledFb = AspectUtils.scaleAspects(fb, AspectUtils.RECIPE_DECAY);
                        AspectList toMerge = scaledFb != null && scaledFb.size() > 0 ? scaledFb : fb;
                        Aspect[] extraAspects = toMerge.getAspects();
                        if (extraAspects == null) extraAspects = new Aspect[0];
                        for (Aspect a : extraAspects) {
                            if (a == null) continue;
                            if (result.getAmount(a) > 0) continue; // keep existing aspect types untouched
                            int amt = toMerge.getAmount(a);
                            if (amt > 0) result.add(a, amt);
                        }
                    }
                }
            }

            if (result != null && result.size() > 0) {
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
     *
     * 从同一物品的其他meta变体继承要素。
     * 仅直接检查缓存和TC API，绝不递归调用 getOrGenerateAspectsFor——
     * 避免循环依赖：例如琥珀方块(meta 0)和琥珀砖(meta 1)会互相触发推导，
     * 在两者完成之前就用不完整/空结果污染缓存。
     */
    private static AspectList deriveFromSameItemMetas(ItemStack stack, int depth, Set<String> visiting) {
        Item item = stack.getItem();
        int myMeta = stack.getItemDamage();

        // Start with meta 0 as the first candidate: it's the most common default variant
        // and most likely to already have aspects registered or cached.
        // 首先加入 meta 0：它是最常见的默认变体，最可能已有要素注册或缓存。
        Set<Integer> candidates = new LinkedHashSet<>();
        candidates.add(0);

        // Scan existing CACHE entries for the same item prefix (e.g. "minecraft:wool@")
        // to discover sibling metas that were already derived in earlier passes.
        // This avoids re-derivation and leverages work already done for other variants.
        // 扫描缓存中同一物品前缀的条目（如 "minecraft:wool@"），
        // 发现已在之前轮次中推导过的兄弟meta，避免重复推导。
        Object nameObj = Item.itemRegistry.getNameForObject(item);
        if (nameObj == null) return null;
        String itemName = nameObj.toString();
        String prefix = itemName + "@";
        for (String cacheKey : AspectUtils.CACHE.keySet()) {
            if (!cacheKey.startsWith(prefix)) continue;
            String metaPart = cacheKey.substring(prefix.length());
            try {
                candidates.add(Integer.parseInt(metaPart));
            } catch (NumberFormatException ignored) {}
        }

        // Also gather metas from creative tabs, OreDict registrations, and recipe outputs.
        // This catches metas that aren't in the cache yet but are known to exist in-game.
        // 还从创造标签页、矿辞注册和配方产出中收集meta，
        // 捕获尚未进入缓存但在游戏中确实存在的meta。
        try {
            Set<Integer> scanMetas = AspectUtils.getMetasToScan(item);
            candidates.addAll(scanMetas);
        } catch (Exception ignored) {}

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

        for (int otherMeta : candidates) {
            ItemStack otherStack = new ItemStack(item, 1, otherMeta);
            String otherKey = AspectUtils.key(otherStack);
            AspectList al = null;

            // Only check already-computed sources (CACHE and TC API) — no recursive derivation.
            // This is the key design decision to prevent circular dependency and cache pollution.
            // 只检查已计算的来源（缓存和TC API）——不递归推导。
            // 这是防止循环依赖和缓存污染的关键设计决策。
            if (AspectUtils.CACHE.containsKey(otherKey)) {
                AspectList cached = AspectUtils.CACHE.get(otherKey);
                if (cached != null && cached.size() > 0) {
                    al = cached.copy();
                }
            }

            if (al == null && ThaumcraftApi.exists(otherStack.getItem(), otherMeta)) {
                AspectList existing = ThaumcraftApiHelper.getObjectAspects(otherStack);
                if (existing != null && existing.size() > 0) {
                    al = existing.copy();
                }
            }

            if (al != null && al.size() > 0) {
                int score = AspectUtils.getAspectTotal(al);
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
        if (best != null) return best.copy();
        return null;
    }

    /**
     * Inherits aspects from OreDictionary equivalents of this ItemStack.
     * This enables cross-mod aspect inheritance: e.g., a GregTech iron ingot registered
     * as "ingotIron" will inherit aspects from the vanilla iron ingot via the same OreDict name.
     * Also works for JAOPCA and similar OreDict-heavy mods.
     *
     * 从矿物辞典等价物继承要素。
     * 这实现了跨模组的要素继承：例如注册为 "ingotIron" 的GT铁锭
     * 可通过相同的矿辞名称从原版铁锭继承要素。也适用于JAOPCA等重度使用矿辞的模组。
     */
    private static AspectList deriveFromOreDictionary(ItemStack stack, int depth, Set<String> visiting) {
        int[] oreIds = OreDictionary.getOreIDs(stack);
        if (oreIds == null || oreIds.length == 0) return null;

        for (int oreId : oreIds) {
            String oreName = OreDictionary.getOreName(oreId);
            // Skip "Unknown" — OreDictionary returns this for unregistered/invalid ore IDs.
            // 跳过 "Unknown"——OreDictionary 对未注册/无效的矿辞ID返回此值。
            if (oreName == null || oreName.isEmpty() || "Unknown".equals(oreName)) continue;

            List<ItemStack> equivalents = OreDictionary.getOres(oreName);
            if (equivalents == null || equivalents.isEmpty()) continue;

            List<ItemStack> others = new ArrayList<>();
            for (ItemStack eq : equivalents) {
                if (eq == null || eq.getItem() == null) continue;
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
                if (eq.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    others.add(new ItemStack(eq.getItem(), 1, 0));
                } else {
                    others.add(eq);
                }
            }

            if (!others.isEmpty()) {
                AspectList al = AspectUtils.getBestFromSlot(others, depth, visiting);
                if (al != null && al.size() > 0) return al.copy();
            }
        }

        return null;
    }

    /**
     * Derives aspects from crafting recipes that produce this ItemStack.
     * Multiple recipes may produce the same item; we evaluate all of them and keep
     * the one yielding the highest total aspect score ("best recipe wins").
     *
     * 从产出此物品的合成配方推导要素。
     * 同一物品可能有多个配方产出；我们评估所有配方并保留总要素分最高的那个（"最优配方胜出"）。
     */
    private static AspectList deriveFromRecipeIndex(ItemStack target, int depth, Set<String> visiting) {
        List<IRecipe> candidates = AspectUtils.RECIPE_INDEX.get(target.getItem());
        if (candidates == null || candidates.isEmpty()) return null;

        AspectList best = null;
        int bestScore = -1;

        // Iterate all recipes that produce this item (there may be multiple).
        // 遍历所有产出此物品的配方（可能有多个）。
        for (IRecipe recipe : candidates) {
            ItemStack output = recipe.getRecipeOutput();
            if (output == null || !AspectUtils.sameItem(output, target)) continue;

            List<List<ItemStack>> inputs = AspectUtils.getRecipeInputs(recipe);
            if (inputs.isEmpty()) continue;

            // Sum up aspects from all ingredient slots of this recipe.
            // 汇总此配方所有材料槽的要素。
            AspectList combined = new AspectList();
            boolean hasInput = false;

            for (List<ItemStack> slot : inputs) {
                // Each slot may have multiple alternatives (OreDict substitutions);
                // getBestFromSlot picks the alternative with the highest aspect score.
                // 每个槽位可能有多个替代品（矿辞替换）；
                // getBestFromSlot 选择要素分最高的替代品。
                AspectList slotAsp = AspectUtils.getBestFromSlot(slot, depth, visiting);
                if (slotAsp != null && slotAsp.size() > 0) {
                    hasInput = true;
                    combined.add(slotAsp);
                }
            }

            if (hasInput && combined.size() > 0) {
                // Scale combined ingredients by RECIPE_DECAY (90% decay, min 1 per aspect).
                // This prevents crafted items from having more aspects than raw materials combined.
                // 按 RECIPE_DECAY（衰减 90%，每种要素至少 1）缩放合并后的材料要素。
                // 防止合成品的要素超过原材料总和。
                AspectList scaled = AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY);
                int score = AspectUtils.getAspectTotal(scaled);
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
     *
     * 从烧炼配方推导要素。
     * 熔炉配方比合成更简单：单一输入 -> 单一输出，
     * 因此不需要"最优"选择——第一个匹配的配方即胜出。
     * 要素来自输入物品并施加50%衰减。
     */
    private static AspectList deriveFromFurnace(ItemStack target, int depth, Set<String> visiting) {
        if (AspectUtils.FURNACE_INDEX == null) return null;
        List<Map.Entry<ItemStack, ItemStack>> candidates = AspectUtils.FURNACE_INDEX.get(target.getItem());
        if (candidates == null || candidates.isEmpty()) return null;

        for (Map.Entry<ItemStack, ItemStack> entry : candidates) {
            ItemStack output = entry.getValue();
            if (output == null || !AspectUtils.sameItem(output, target)) continue;

            ItemStack input = entry.getKey();
            if (input == null || input.getItem() == null) continue;

            // Derive from input and apply RECIPE_DECAY; return immediately on first match.
            // 从输入推导并施加 RECIPE_DECAY；首次匹配即返回。
            AspectList inputAsp = getOrGenerateAspectsFor(input, depth, visiting);
            if (inputAsp != null && inputAsp.size() > 0) {
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
     *
     * 从TC配方推导要素（奥术合成、注魔、坩埚）。
     * 处理4种TC配方类型：有序奥术、无序奥术、注魔和坩埚——各有不同的输入结构。
     * 使用与合成配方相同的"最优配方胜出"策略。
     */
    private static AspectList deriveFromTCRecipes(ItemStack target, int depth, Set<String> visiting) {
        if (AspectUtils.TC_RECIPE_INDEX == null) return null;
        List<Object> candidates = AspectUtils.TC_RECIPE_INDEX.get(target.getItem());
        if (candidates == null || candidates.isEmpty()) return null;

        AspectList best = null;
        int bestScore = -1;

        for (Object obj : candidates) {
            List<List<ItemStack>> inputs = null;
            ItemStack recipeOut = null;

            // ShapedArcaneRecipe / ShapelessArcaneRecipe: standard grid inputs, resolved via OreDict.
            // 有序/无序奥术配方：标准网格输入，通过矿辞解析。
            if (obj instanceof ShapedArcaneRecipe sar) {
                recipeOut = sar.getRecipeOutput();
                inputs = new ArrayList<>();
                for (Object o : sar.getInput()) {
                    inputs.add(AspectUtils.resolveOreInput(o));
                }
            } else if (obj instanceof ShapelessArcaneRecipe slr) {
                recipeOut = slr.getRecipeOutput();
                inputs = new ArrayList<>();
                for (Object o : slr.getInput()) {
                    inputs.add(AspectUtils.resolveOreInput(o));
                }
            } else if (obj instanceof InfusionRecipe ir) {
                // InfusionRecipe has a special structure: center item + surrounding component array.
                // The output can be non-ItemStack (e.g., enchantment), so we must check.
                // 注魔配方有特殊结构：中心物品 + 周围组件数组。
                // 产出可能不是ItemStack（如附魔），因此必须检查。
                Object irOut = ir.getRecipeOutput();
                if (!(irOut instanceof ItemStack)) continue;
                recipeOut = (ItemStack) irOut;
                inputs = new ArrayList<>();
                ItemStack center = ir.getRecipeInput();
                if (center != null) inputs.add(Collections.singletonList(center.copy()));
                ItemStack[] components = ir.getComponents();
                if (components != null) {
                    for (ItemStack c : components) {
                        if (c != null) inputs.add(Collections.singletonList(c.copy()));
                    }
                }
            } else if (obj instanceof CrucibleRecipe cr) {
                // CrucibleRecipe catalyst can be either a single ItemStack or an OreDict List<ItemStack>.
                // 坩埚配方的催化剂可以是单个ItemStack或矿辞的 List<ItemStack>。
                recipeOut = cr.getRecipeOutput();
                inputs = new ArrayList<>();
                Object cat = cr.catalyst;
                if (cat instanceof ItemStack) {
                    inputs.add(Collections.singletonList(((ItemStack) cat).copy()));
                } else if (cat instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<ItemStack> oreList = (List<ItemStack>) cat;
                    inputs.add(new ArrayList<>(oreList));
                }
            } else {
                // Unknown TC recipe type: try reflection to extract output and inputs.
                // Some TC addons (Thaumic Tinkerer, Automagy, etc.) may define custom recipe classes.
                // 未知的 TC 配方类型：尝试反射提取输出和输入。
                // 部分 TC 附属（神秘匠魂、Automagy 等）可能定义了自定义配方类。
                try {
                    Method outMethod = obj.getClass()
                        .getMethod("getRecipeOutput");
                    Object outObj = outMethod.invoke(obj);
                    if (outObj instanceof ItemStack) {
                        recipeOut = (ItemStack) outObj;
                        // Reuse the generic reflection extractor for inputs
                        // 复用通用反射提取器来获取输入
                        if (obj instanceof IRecipe) {
                            inputs = AspectUtils.getRecipeInputs((IRecipe) obj);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (recipeOut == null || !AspectUtils.sameItem(recipeOut, target) || inputs == null || inputs.isEmpty())
                continue;

            // Same aggregation logic as crafting recipes: sum all slots, scale, keep best.
            // 与合成配方相同的聚合逻辑：汇总所有槽位、缩放、保留最优。
            AspectList combined = new AspectList();
            boolean hasInput = false;
            for (List<ItemStack> slot : inputs) {
                AspectList slotAsp = AspectUtils.getBestFromSlot(slot, depth, visiting);
                if (slotAsp != null && slotAsp.size() > 0) {
                    hasInput = true;
                    combined.add(slotAsp);
                }
            }

            if (hasInput && combined.size() > 0) {
                AspectList scaled = AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY);
                int score = AspectUtils.getAspectTotal(scaled);
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
     *
     * Name parsing strategy: tries multiple patterns to extract the material name from the
     * fluid registry name, because different mods use different naming conventions
     * (e.g., TiC uses "molten.iron", GT uses "moltenIron", some use "liquid_iron").
     *
     * 通过在矿物辞典中查找对应固体材料并添加 ignis + aqua 来推导熔融/液态流体的要素。
     *
     * 名称解析策略：尝试多种模式从流体注册名中提取材料名，因为不同模组使用不同的命名规范
     * （如TiC用 "molten.iron"，GT用 "moltenIron"，还有的用 "liquid_iron"）。
     */
    static AspectList deriveFluidFromMaterial(String fluidName) {
        String lower = fluidName.toLowerCase();

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

        if (material != null && !material.isEmpty()) {
            // Clean separators and capitalize for OreDict lookup:
            // e.g., "pig_iron" -> "pigIron" -> "PigIron" for "ingotPigIron"
            // 清理分隔符并大写化以用于矿辞查找：
            // 如 "pig_iron" -> "pigIron" -> "PigIron" 对应 "ingotPigIron"
            material = material.replace("_", "")
                .replace(".", "");

            String matCap = material.substring(0, 1)
                .toUpperCase() + material.substring(1);
            // Try OreDict prefixes in priority order: ingot first (most likely to have aspects
            // since TC registers aspects for ingots), then ore, dust, gem, block.
            // 按优先级顺序尝试矿辞前缀：锭优先（最可能有要素，因为TC为锭注册要素），
            // 然后是矿石、粉、宝石、方块。
            String[] prefixes = { "ingot", "ore", "dust", "gem", "block" };
            for (String pfx : prefixes) {
                String oreName = pfx + matCap;
                List<ItemStack> ores = OreDictionary.getOres(oreName);
                if (ores != null && !ores.isEmpty()) {
                    for (ItemStack ore : ores) {
                        if (ore == null || ore.getItem() == null) continue;
                        AspectList srcAsp = getOrGenerateAspectsFor(ore, 0, new HashSet<>());
                        if (srcAsp != null && srcAsp.size() > 0) {
                            AspectList result = srcAsp.copy();
                            // Add ignis (fire/heat from the melting process) and aqua (liquid state).
                            // These represent the physical transformation from solid to molten fluid.
                            // 添加 ignis（熔炼过程的火/热）和 aqua（液态）。
                            // 代表从固体到熔融流体的物理转变。
                            Aspect ignis = AspectUtils.getAspect("ignis");
                            if (ignis != null) result.add(ignis, 2);
                            Aspect aqua = AspectUtils.getAspect("aqua");
                            if (aqua != null) result.add(aqua, 1);
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
