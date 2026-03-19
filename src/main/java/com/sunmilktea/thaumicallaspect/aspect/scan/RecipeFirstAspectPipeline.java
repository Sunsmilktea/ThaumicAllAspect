package com.sunmilktea.thaumicallaspect.aspect.scan;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;

import com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumcraft.api.crafting.ShapedArcaneRecipe;
import thaumcraft.api.crafting.ShapelessArcaneRecipe;

/**
 * Recipe-first aspect pipeline: iterate recipes (not items), assign output aspects when all
 * inputs have aspects; record outputs that had empty input for next round; up to 6 rounds.
 * Ensures e.g. A+B=C, A=B, only A has 3 → after round 1 B gets 3, after round 2 C gets 6.
 * <p>
 * 配方优先管线：按配方迭代（不按物品），当所有输入均有源质时赋 output 源质；
 * 记录「第一次 input 为空」的 output 下一轮再算；最多 6 轮。
 */
public enum RecipeFirstAspectPipeline {
    ;

    /** Max rounds for recipe-first propagation, configurable via thaumicallaspect.cfg (default 6). */
    private static int maxRounds = 6;

    public static void setMaxRounds(final int rounds) {
        // Guardrail: keep it in a sane range.
        if (rounds < 1) {
            RecipeFirstAspectPipeline.maxRounds = 1;
        } else if (rounds > 32) {
            RecipeFirstAspectPipeline.maxRounds = 32;
        } else {
            RecipeFirstAspectPipeline.maxRounds = rounds;
        }
    }

    /**
     * Runs the recipe-first pipeline: collect all recipes from current indices, then run
     * up to 6 rounds. Each round: for (pending or all) recipes, if all inputs have aspect
     * then sum inputs, scale by RECIPE_DECAY, register output; else add output to pending.
     * Call this only when aspect-cache.cfg does NOT exist (full recompute).
     * <p>
     * 运行配方优先管线：从当前索引收集所有配方，然后最多 6 轮。每轮：对（待处理或全部）配方，
     * 若所有输入均有源质则求和并乘以 RECIPE_DECAY 注册 output；否则将 output 加入待处理。
     */
    public static void run() {
        final List<RecipeRecord> all = RecipeFirstAspectPipeline.collectRecipeRecords();
        if (all.isEmpty()) {
            ModFileLogger.info("[ThaumicAllAspect] " + tr("No recipes to process in recipe-first pipeline."));
            return;
        }

        final Set<String> pending = new HashSet<>();

        for (int round = 1; RecipeFirstAspectPipeline.maxRounds >= round; round++) {
            final Set<String> toProcess = (1 == round) ? new HashSet<>() : new HashSet<>(pending);
            if (1 == round) {
                for (final RecipeRecord r : all) {
                    toProcess.add(r.key());
                }
            }
            pending.clear();

            int roundRegistered = 0;
            for (final RecipeRecord rec : all) {
                final String outKey = rec.key();
                if (!toProcess.contains(outKey)) continue;

                final AspectList combined = new AspectList();
                boolean allHaveAspect = true;
                for (final List<ItemStack> slot : rec.inputs) {
                    final AspectList slotAsp = AspectUtils.getBestFromSlotExistingOnly(slot);
                    if (null != slotAsp && 0 < slotAsp.size()) {
                        combined.add(slotAsp);
                    } else {
                        allHaveAspect = false;
                        break;
                    }
                }

                if (!allHaveAspect || 0 == combined.size()) {
                    pending.add(outKey);
                    continue;
                }

                // Skip if output already has aspects (TC or pre-loaded); do not overwrite.
                // 若产出已有源质（TC 或预加载）则跳过，不覆盖。
                if (null != AspectUtils.getExistingAspectsOnly(rec.outputStack)) continue;

                final AspectList scaled = AspectUtils
                    .ensureMinOnePerAspect(AspectUtils.scaleAspects(combined, AspectUtils.RECIPE_DECAY));
                if (null == scaled || 0 == scaled.size()) {
                    pending.add(outKey);
                    continue;
                }

                ThaumcraftApi.registerObjectTag(rec.outputStack, scaled.copy());
                AspectUtils.CACHE.put(outKey, scaled.copy());
                AspectUtils.statNewlyRegistered++;
                roundRegistered++;
                pending.remove(outKey);
            }

            ModFileLogger.info(
                String.format(
                    "[ThaumicAllAspect] %s %d] %s %d %s %d %s",
                    tr("[Pass"),
                    round,
                    tr("Recipe-first:"),
                    roundRegistered,
                    tr("registered,"),
                    pending.size(),
                    tr("pending")));

            if (pending.isEmpty() || 0 == roundRegistered) break;
        }
    }

    private static List<RecipeRecord> collectRecipeRecords() {
        final List<RecipeRecord> out = new ArrayList<>();

        if (null != AspectUtils.RECIPE_INDEX) {
            for (final Map.Entry<net.minecraft.item.Item, List<IRecipe>> e : AspectUtils.RECIPE_INDEX.entrySet()) {
                if (null == e.getValue()) continue;
                for (final IRecipe recipe : e.getValue()) {
                    final ItemStack output;
                    try {
                        output = recipe.getRecipeOutput();
                    } catch (final Exception ex) {
                        continue;
                    }
                    if (null == output || null == output.getItem()) continue;
                    final ItemStack normOut = AspectUtils.normalizeForLookup(output);
                    final List<List<ItemStack>> inputs = AspectUtils.getRecipeInputs(recipe);
                    if (inputs.isEmpty()) continue;
                    out.add(new RecipeRecord(normOut, inputs));
                }
            }
        }

        if (null != AspectUtils.FURNACE_INDEX) {
            for (final Map.Entry<net.minecraft.item.Item, List<Map.Entry<ItemStack, ItemStack>>> e : AspectUtils.FURNACE_INDEX
                .entrySet()) {
                if (null == e.getValue()) continue;
                for (final Map.Entry<ItemStack, ItemStack> entry : e.getValue()) {
                    final ItemStack in = entry.getKey();
                    final ItemStack output = entry.getValue();
                    if (null == output || null == output.getItem() || null == in || null == in.getItem()) continue;
                    final ItemStack normOut = AspectUtils.normalizeForLookup(output);
                    final List<List<ItemStack>> inputs = Collections
                        .singletonList(Collections.singletonList(AspectUtils.normalizeForLookup(in)));
                    out.add(new RecipeRecord(normOut, inputs));
                }
            }
        }

        if (null != AspectUtils.TC_RECIPE_INDEX) {
            for (final Map.Entry<net.minecraft.item.Item, List<Object>> e : AspectUtils.TC_RECIPE_INDEX.entrySet()) {
                if (null == e.getValue()) continue;
                for (final Object obj : e.getValue()) {
                    List<List<ItemStack>> inputs = null;
                    ItemStack recipeOut = null;

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
                        final Object irOut = ir.getRecipeOutput();
                        if (!(irOut instanceof ItemStack)) continue;
                        recipeOut = (ItemStack) irOut;
                        inputs = new ArrayList<>();
                        final ItemStack center = ir.getRecipeInput();
                        if (null != center)
                            inputs.add(Collections.singletonList(AspectUtils.normalizeForLookup(center.copy())));
                        final ItemStack[] components = ir.getComponents();
                        if (null != components) {
                            for (final ItemStack c : components) {
                                if (null != c)
                                    inputs.add(Collections.singletonList(AspectUtils.normalizeForLookup(c.copy())));
                            }
                        }
                    } else if (obj instanceof CrucibleRecipe) {
                        final CrucibleRecipe cr = (CrucibleRecipe) obj;
                        recipeOut = cr.getRecipeOutput();
                        inputs = new ArrayList<>();
                        final Object cat = cr.catalyst;
                        if (null != cat) inputs.add(AspectUtils.resolveOreInput(cat));
                    }

                    if (null == recipeOut || null == recipeOut.getItem() || null == inputs || inputs.isEmpty())
                        continue;
                    final ItemStack normOut = AspectUtils.normalizeForLookup(recipeOut);
                    out.add(new RecipeRecord(normOut, inputs));
                }
            }
        }

        return out;
    }

    /** One recipe: output stack (normalized) and per-slot input alternatives. */
    static final class RecipeRecord {

        final ItemStack outputStack;
        final List<List<ItemStack>> inputs;

        RecipeRecord(final ItemStack outputStack, final List<List<ItemStack>> inputs) {
            this.outputStack = outputStack;
            this.inputs = inputs;
        }

        String key() {
            return AspectUtils.key(this.outputStack);
        }
    }
}
