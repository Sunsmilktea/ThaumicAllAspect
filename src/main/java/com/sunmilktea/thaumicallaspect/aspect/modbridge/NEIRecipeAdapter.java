package com.sunmilktea.thaumicallaspect.aspect.modbridge;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

import com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.FurnaceRecipeHandler;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;

/**
 * NEI integration: use GTNH NEI's TemplateRecipeHandler-based API (no guessing).
 * 从 GTNH NEI 的 TemplateRecipeHandler 中读取配方，填充 RECIPE_INDEX / FURNACE_INDEX，
 * 供配方优先 8 轮管线使用。
 */
public enum NEIRecipeAdapter {
    ;

    /**
     * Fills AspectUtils.RECIPE_INDEX and FURNACE_INDEX from NEI.
     * Returns true if at least crafting or furnace index was populated.
     */
    public static boolean fillFromNEI() {
        boolean craft = false;
        boolean furnace = false;
        try {
            craft = NEIRecipeAdapter.fillCraftingFromNEI();
        } catch (final Throwable t) {
            ModFileLogger.warn(
                "[ThaumicAllAspect] NEI " + tr("recipe source")
                    + " (crafting) "
                    + tr("failed")
                    + ": "
                    + (null != t.getMessage() ? t.getMessage()
                        : t.getClass()
                            .getSimpleName()));
        }
        try {
            furnace = NEIRecipeAdapter.fillFurnaceFromNEI();
        } catch (final Throwable t) {
            ModFileLogger.warn(
                "[ThaumicAllAspect] NEI " + tr("recipe source")
                    + " (furnace) "
                    + tr("failed")
                    + ": "
                    + (null != t.getMessage() ? t.getMessage()
                        : t.getClass()
                            .getSimpleName()));
        }
        if (craft || furnace) {
            ModFileLogger
                .info("[ThaumicAllAspect] NEI " + tr("recipe source") + ": crafting=" + craft + ", furnace=" + furnace);
            return true;
        }
        return false;
    }

    /**
     * Uses GuiCraftingRecipe.getCraftingHandlers(\"all\", ...) to obtain all ICraftingHandler
     * instances that have recipes loaded, then for each TemplateRecipeHandler extracts
     * result + ingredients via numRecipes/getResultStack/getIngredients.
     */
    private static boolean fillCraftingFromNEI() {
        final ArrayList<ICraftingHandler> handlers = GuiCraftingRecipe.getCraftingHandlers("all");
        if (null == handlers || handlers.isEmpty()) return false;

        AspectUtils.RECIPE_INDEX = new HashMap<>();
        if (null == AspectUtils.RECIPE_OUTPUT_METAS) AspectUtils.RECIPE_OUTPUT_METAS = new HashMap<>();
        int count = 0;

        for (final ICraftingHandler h : handlers) {
            if (!(h instanceof TemplateRecipeHandler)) continue;
            final TemplateRecipeHandler th = (TemplateRecipeHandler) h;
            final List<TemplateRecipeHandler.CachedRecipe> recipes = th.arecipes;
            final int n = null != recipes ? recipes.size() : 0;
            for (int i = 0; i < n; i++) {
                final TemplateRecipeHandler.CachedRecipe rec = recipes.get(i);
                final PositionedStack outPos = rec.getResult();
                if (null == outPos || null == outPos.item || null == outPos.item.getItem()) continue;
                final ItemStack out = outPos.item;
                final Item outItem = out.getItem();
                final int outMeta = out.getItemDamage();

                final List<PositionedStack> inPosList = rec.getIngredients();
                if (null == inPosList || inPosList.isEmpty()) continue;
                final List<ItemStack> flatInputs = new ArrayList<>();
                for (final PositionedStack ps : inPosList) {
                    if (null == ps) continue;
                    if (null != ps.items && 0 < ps.items.length) {
                        for (final ItemStack cand : ps.items) {
                            if (null != cand && null != cand.getItem()) flatInputs.add(cand);
                        }
                    } else if (null != ps.item && null != ps.item.getItem()) {
                        flatInputs.add(ps.item);
                    }
                }
                if (flatInputs.isEmpty()) continue;

                final IRecipe recipe = new NEIRecipeStub(out, flatInputs);
                List<IRecipe> perItem = AspectUtils.RECIPE_INDEX.get(outItem);
                if (null == perItem) {
                    perItem = new ArrayList<>();
                    AspectUtils.RECIPE_INDEX.put(outItem, perItem);
                }
                perItem.add(recipe);
                count++;

                if (0 <= outMeta && OreDictionary.WILDCARD_VALUE != outMeta) {
                    Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                    if (null == ms) {
                        ms = new LinkedHashSet<>();
                        AspectUtils.RECIPE_OUTPUT_METAS.put(outItem, ms);
                    }
                    ms.add(outMeta);
                }
            }
        }
        return 0 < count;
    }

    /**
     * Uses GuiCraftingRecipe.getCraftingHandlers(\"smelting\", ...) to obtain furnace
     * handlers (FurnaceRecipeHandler extends TemplateRecipeHandler) and builds
     * FURNACE_INDEX as input→output pairs.
     */
    private static boolean fillFurnaceFromNEI() {
        final ArrayList<ICraftingHandler> handlers = GuiCraftingRecipe.getCraftingHandlers("smelting");
        if (null == handlers || handlers.isEmpty()) return false;

        AspectUtils.FURNACE_INDEX = new HashMap<>();
        if (null == AspectUtils.RECIPE_OUTPUT_METAS) AspectUtils.RECIPE_OUTPUT_METAS = new HashMap<>();
        int count = 0;

        for (final ICraftingHandler h : handlers) {
            if (!(h instanceof TemplateRecipeHandler)) continue;
            final TemplateRecipeHandler th = (TemplateRecipeHandler) h;
            // Prefer concrete FurnaceRecipeHandler but any TemplateRecipeHandler with smelting overlay will work
            final boolean isFurnace = h instanceof FurnaceRecipeHandler
                || "smelting".equalsIgnoreCase(th.getOverlayIdentifier());
            if (!isFurnace) continue;

            final List<TemplateRecipeHandler.CachedRecipe> recipes = th.arecipes;
            final int n = null != recipes ? recipes.size() : 0;
            for (int i = 0; i < n; i++) {
                final TemplateRecipeHandler.CachedRecipe rec = recipes.get(i);
                final PositionedStack outPos = rec.getResult();
                final List<PositionedStack> inPosList = rec.getIngredients();
                if (null == outPos || null == outPos.item || null == outPos.item.getItem()) continue;
                if (null == inPosList || inPosList.isEmpty()) continue;

                ItemStack in = null;
                final PositionedStack firstIn = inPosList.get(0);
                if (null != firstIn) {
                    if (null != firstIn.items && 0 < firstIn.items.length) {
                        in = firstIn.items[0];
                    } else {
                        in = firstIn.item;
                    }
                }
                if (null == in || null == in.getItem()) continue;

                final ItemStack out = outPos.item;
                final Item outItem = out.getItem();
                final int outMeta = out.getItemDamage();

                List<Map.Entry<ItemStack, ItemStack>> fList = AspectUtils.FURNACE_INDEX.get(outItem);
                if (null == fList) {
                    fList = new ArrayList<>();
                    AspectUtils.FURNACE_INDEX.put(outItem, fList);
                }
                fList.add(new java.util.AbstractMap.SimpleEntry<>(in, out));
                count++;

                if (0 <= outMeta && OreDictionary.WILDCARD_VALUE != outMeta) {
                    Set<Integer> ms = AspectUtils.RECIPE_OUTPUT_METAS.get(outItem);
                    if (null == ms) {
                        ms = new LinkedHashSet<>();
                        AspectUtils.RECIPE_OUTPUT_METAS.put(outItem, ms);
                    }
                    ms.add(outMeta);
                }
            }
        }
        return 0 < count;
    }

    /**
     * Minimal IRecipe stub built from NEI entry (output + flat inputs) for use with getRecipeInputs.
     */
    private static class NEIRecipeStub implements IRecipe {

        private final ItemStack output;
        private final List<ItemStack> inputs;

        NEIRecipeStub(final ItemStack output, final List<ItemStack> inputs) {
            this.output = output;
            this.inputs = inputs;
        }

        @Override
        public ItemStack getRecipeOutput() {
            return this.output;
        }

        @Override
        public int getRecipeSize() {
            return null != inputs ? this.inputs.size() : 0;
        }

        @Override
        public boolean matches(final InventoryCrafting inv, final World world) {
            return false;
        }

        @Override
        public ItemStack getCraftingResult(final InventoryCrafting inv) {
            return null != output ? this.output.copy() : null;
        }

        public List<ItemStack> getInputs() {
            return this.inputs;
        }
    }
}
