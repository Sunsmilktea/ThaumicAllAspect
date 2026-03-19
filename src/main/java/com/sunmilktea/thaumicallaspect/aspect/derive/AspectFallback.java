package com.sunmilktea.thaumicallaspect.aspect.derive;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;

import com.sunmilktea.thaumicallaspect.config.FallbackConfig;

import baubles.api.IBauble;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

/**
 * Provides three complementary strategies for assigning aspects to items that lack recipe-based derivation:
 * 提供三种互补的策略，为缺乏配方推导的物品分配要素：
 *
 * <ol>
 * <li>{@link #deriveFromType} — Infers aspects from the item's Java class hierarchy and block material.
 * Handles ItemBlock (checks Material: rock/ground/iron and specific vanilla blocks),
 * ItemArmor, ItemTool/ItemHoe/ItemShears, and food items (ItemFood or eat/drink action).
 * 根据物品的 Java 类继承关系和方块材质推断要素。
 * 处理 ItemBlock（检查 Material：rock/ground/iron 及特定原版方块）、
 * ItemArmor、ItemTool/ItemHoe/ItemShears，以及食物物品（ItemFood 或 eat/drink 动作）。</li>
 *
 * <li>{@link #applySpecialRules} — Enforces minimum aspect guarantees for specific item categories,
 * applied AFTER all other derivation methods. Food always gets at least fames=4;
 * baubles (IBauble) always get metallum=4 and tutamen=4 if they currently have 0.
 * 为特定物品类别强制最低要素保证，在所有其他推导方法之后应用。
 * 食物始终获得至少 fames=4；饰品（IBauble）在当前为 0 时始终获得 metallum=4 和 tutamen=4。</li>
 *
 * <li>{@link #createGeneralFallback} — Keyword-based heuristic matching against the item's
 * unlocalizedName and registryName as an absolute last resort. Categories are NOT mutually exclusive;
 * an item can match multiple categories and aspects will stack.
 * 基于关键词的启发式匹配，对物品的 unlocalizedName 和 registryName 进行匹配，作为绝对最后手段。
 * 类别之间不互斥；一个物品可以匹配多个类别，要素会叠加。</li>
 * </ol>
 */
public class AspectFallback {

    /**
     * Derives aspects by inspecting the item's Java class hierarchy and, for blocks, the block's Material.
     * 通过检查物品的 Java 类继承关系来推导要素，对方块则额外检查其 Material。
     *
     * <p>
     * Resolution order (first match wins, returns immediately):
     * 解析顺序（首次匹配即返回）：
     * <ol>
     * <li><b>ItemBlock</b> — First checks specific vanilla blocks (log/planks → arbor=8).
     * Then checks block Material: rock/ground/iron → terra+perditio, iron/rock → metallum.
     * ItemBlock — 首先检查特定原版方块（原木/木板 → arbor=8）。
     * 然后检查方块 Material：rock/ground/iron → terra+perditio，iron/rock → metallum。</li>
     * <li><b>ItemArmor</b> — Always assigns tutamen=8 (protection) + metallum=4.
     * ItemArmor — 始终分配 tutamen=8（防护）+ metallum=4。</li>
     * <li><b>Tools</b> — Checks instanceof ItemTool/ItemHoe/ItemShears first. If that fails,
     * tries {@code getHarvestLevel()} as a fallback for modded tools that don't extend vanilla classes
     * (e.g., GT tools). Assigns instrumentum=5 + metallum=3.
     * 工具 — 先检查 instanceof ItemTool/ItemHoe/ItemShears。若失败，
     * 则尝试 getHarvestLevel() 作为兜底，以支持不继承原版类的模组工具（如 GT 工具）。
     * 分配 instrumentum=5 + metallum=3。</li>
     * <li><b>Food</b> — Checks both ItemFood class and use-action (eat/drink) via
     * {@link AspectUtils#isFoodLike} for mod compatibility. Assigns fames=4 + victus=3 + aqua=1.
     * 食物 — 通过 {@link AspectUtils#isFoodLike} 同时检查 ItemFood 类和使用动作（eat/drink），
     * 以兼容模组。分配 fames=4 + victus=3 + aqua=1。</li>
     * </ol>
     *
     * <p>
     * Returns {@code null} if no type can be determined, signaling the caller to try keyword fallback.
     * All aspect lookups are null-safe: {@link AspectUtils#getAspect} returns null if the aspect's mod isn't loaded.
     * 如果无法确定类型则返回 null，通知调用者尝试关键词兜底。
     * 所有要素查找都是 null 安全的：如果要素所属模组未加载，getAspect() 会返回 null。
     */
    static AspectList deriveFromType(ItemStack stack) {
        try {
            final Item item = stack.getItem();
            final AspectList al = new AspectList();

            if (item instanceof ItemBlock) {
                final ItemBlock ib = (ItemBlock) item;
                final Block b = ib.field_150939_a;
                final Material m = b.getMaterial();

                // Specific vanilla blocks: logs/planks → arbor
                // 特定原版方块：原木/木板 → arbor
                if (b == Blocks.log || b == Blocks.log2 || b == Blocks.planks) {
                    final Aspect arbor = AspectUtils.getAspect("arbor");
                    if (null != arbor) al.add(arbor, 8);
                    return al;
                }

                // Quartz block: OreDict is tried first in AspectDeriver; this is fallback when OreDict has no donor.
                // 石英块：推导链中会先走矿物辞典；此处为矿辞无 donor 时的类型兜底。
                if (b == Blocks.quartz_block) {
                    final Aspect vit = AspectUtils.getAspect("vitreus");
                    final Aspect ter = AspectUtils.getAspect("terra");
                    final Aspect pot = AspectUtils.getAspect("potentia");
                    if (null != vit) al.add(vit, 4);
                    if (null != ter) al.add(ter, 2);
                    if (null != pot) al.add(pot, 2);
                    return al;
                }

                // Hard mineral materials: rock, ground, iron → terra + perditio
                // 硬质矿物材料：岩石、地面、铁 → terra + perditio
                if (m == Material.rock || m == Material.ground || m == Material.iron) {
                    final Aspect ter = AspectUtils.getAspect("terra");
                    final Aspect per = AspectUtils.getAspect("perditio");
                    if (null != ter) al.add(ter, 4);
                    if (null != per) al.add(per, 2);
                }
                if (m == Material.iron || m == Material.rock) {
                    final Aspect met = AspectUtils.getAspect("metallum");
                    if (null != met) al.add(met, 4);
                }

                // Grass/dirt-like blocks: Material.grass (grass block, path block) → terra + herba
                // 草地/泥土类方块：Material.grass（草方块、草径方块）→ terra + herba
                if (m == Material.grass) {
                    final Aspect ter = AspectUtils.getAspect("terra");
                    final Aspect herb = AspectUtils.getAspect("herba");
                    if (null != ter) al.add(ter, 4);
                    if (null != herb) al.add(herb, 2);
                }

                // Sand blocks: Material.sand → terra + perditio (loose, crumbly earth)
                // 沙子方块：Material.sand → terra + perditio（松散、易碎的大地）
                if (m == Material.sand) {
                    final Aspect ter = AspectUtils.getAspect("terra");
                    final Aspect per = AspectUtils.getAspect("perditio");
                    if (null != ter) al.add(ter, 4);
                    if (null != per) al.add(per, 3);
                }

                // Clay blocks: Material.clay → terra + aqua (wet earth)
                // 黏土方块：Material.clay → terra + aqua（湿土）
                if (m == Material.clay) {
                    final Aspect ter = AspectUtils.getAspect("terra");
                    final Aspect aqu = AspectUtils.getAspect("aqua");
                    if (null != ter) al.add(ter, 4);
                    if (null != aqu) al.add(aqu, 2);
                }

                // Snow/ice blocks: Material.snow, Material.ice, Material.craftedSnow → gelum + aqua
                // 雪/冰方块：Material.snow、Material.ice、Material.craftedSnow → gelum + aqua
                if (m == Material.snow || m == Material.ice || m == Material.craftedSnow) {
                    final Aspect gel = AspectUtils.getAspect("gelum");
                    final Aspect aqu = AspectUtils.getAspect("aqua");
                    if (null != gel) al.add(gel, 4);
                    if (null != aqu) al.add(aqu, 2);
                }

                // Wooden blocks: Material.wood → arbor
                // 木质方块：Material.wood → arbor
                if (m == Material.wood) {
                    final Aspect arbor = AspectUtils.getAspect("arbor");
                    if (null != arbor) al.add(arbor, 4);
                }

                // Plants/vines: Material.plants, Material.vine → herba + victus
                // 植物/藤蔓：Material.plants、Material.vine → herba + victus
                if (m == Material.plants || m == Material.vine) {
                    final Aspect herb = AspectUtils.getAspect("herba");
                    final Aspect vic = AspectUtils.getAspect("victus");
                    if (null != herb) al.add(herb, 4);
                    if (null != vic) al.add(vic, 2);
                }

                // Cloth/sponge: Material.cloth → pannus; Material.sponge → aqua + vacuos
                // 布料/海绵：Material.cloth → pannus；Material.sponge → aqua + vacuos
                if (m == Material.cloth) {
                    final Aspect pan = AspectUtils.getAspect("pannus");
                    if (null != pan) al.add(pan, 4);
                }
                if (m == Material.sponge) {
                    final Aspect aqu = AspectUtils.getAspect("aqua");
                    final Aspect vac = AspectUtils.getAspect("vacuos");
                    if (null != aqu) al.add(aqu, 4);
                    if (null != vac) al.add(vac, 3);
                }

                return AspectUtils.hasPositiveAspectAmount(al) ? al : null;
            }

            if (item instanceof ItemArmor) {
                final Aspect tut = AspectUtils.getAspect("tutamen");
                final Aspect met = AspectUtils.getAspect("metallum");
                if (null != tut) al.add(tut, 8);
                if (null != met) al.add(met, 4);
                return al;
            }

            boolean isTool = item instanceof ItemTool || item instanceof ItemHoe || item instanceof ItemShears;
            if (!isTool) {
                try {
                    isTool = 0 <= item.getHarvestLevel(stack, "pickaxe") || 0 <= item.getHarvestLevel(stack, "axe")
                        || 0 <= item.getHarvestLevel(stack, "shovel");
                } catch (final Exception ignored) {}
            }
            if (isTool) {
                final Aspect inst = AspectUtils.getAspect("instrumentum");
                final Aspect met = AspectUtils.getAspect("metallum");
                if (null != inst) al.add(inst, 5);
                if (null != met) al.add(met, 3);
                return al;
            }

            if (AspectUtils.isFoodLike(item, stack)) {
                final Aspect fam = AspectUtils.getAspect("fames");
                final Aspect vic = AspectUtils.getAspect("victus");
                final Aspect aqu = AspectUtils.getAspect("aqua");
                if (null != fam) al.add(fam, 4);
                if (null != vic) al.add(vic, 3);
                if (null != aqu) al.add(aqu, 1);
                return al;
            }

            return null;
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Applies minimum aspect guarantees for specific item categories, regardless of derivation source.
     * 为特定物品类别应用最低要素保证，无论要素来源是什么。
     *
     * <p>
     * This method is applied AFTER all other derivation methods (recipe, type, keyword), even if they
     * succeeded. It "tops up" aspects to meet category-specific minimums:
     * 此方法在所有其他推导方法（配方、类型、关键词）之后应用，即使它们已经成功。
     * 它将要素"补充"到类别特定的最低值：
     * <ul>
     * <li><b>Food</b> — Always at least fames=4. If recipe derivation only gave fames=2,
     * this adds 2 more to reach the minimum. Prevents food items from feeling "empty" in Thaumometer.
     * 食物 — 始终至少 fames=4。如果配方推导只给了 fames=2，则再补充 2 以达到最低值。
     * 防止食物物品在神秘学透镜中显得"空洞"。</li>
     * <li><b>Baubles (IBauble)</b> — Gets metallum=4 and tutamen=4 if their current amount is 0.
     * Unlike food, this only applies when the aspect is completely absent (not a top-up).
     * 饰品（IBauble）— 如果当前数量为 0，则获得 metallum=4 和 tutamen=4。
     * 与食物不同，这仅在要素完全缺失时生效（不是补充）。</li>
     * </ul>
     *
     * <p>
     * Works on a copy of the input to preserve immutability of the original AspectList.
     * Returns {@code null} only if the result is completely empty (no aspects at all).
     * 在输入的副本上操作，以保持原始 AspectList 的不可变性。
     * 仅当结果完全为空（没有任何要素）时返回 null。
     */
    static AspectList applySpecialRules(final ItemStack stack, final AspectList base) {
        final AspectList al = null != base ? base.copy() : new AspectList();
        final Item item = stack.getItem();

        if (AspectUtils.isFoodLike(item, stack)) {
            final Aspect fam = AspectUtils.getAspect("fames");
            if (null != fam) {
                final int hunger = al.getAmount(fam);
                if (4 > hunger) al.add(fam, 4 - hunger);
            }
        }

        if (item instanceof IBauble) {
            final Aspect met = AspectUtils.getAspect("metallum");
            final Aspect tut = AspectUtils.getAspect("tutamen");
            if (null != met && 0 == al.getAmount(met)) al.add(met, 4);
            if (null != tut && 0 == al.getAmount(tut)) al.add(tut, 4);
        }

        return AspectUtils.hasPositiveAspectAmount(al) ? al : null;
    }

    /**
     * Assigns aspects via keyword-based heuristic matching — the absolute last resort when all other
     * derivation methods (recipe traversal, type inspection) have failed.
     * 通过关键词启发式匹配分配要素 — 当所有其他推导方法（配方遍历、类型检查）都失败时的绝对最后手段。
     *
     * <p>
     * Builds a search string from {@code unlocalizedName + " " + registryName} (lowercased, with
     * "tile."/"item." prefix stripped) and scans it against categorized keyword lists.
     * 从 unlocalizedName + " " + registryName（小写化，去除 "tile."/"item." 前缀）构建搜索字符串，
     * 然后对分类关键词列表进行扫描。
     *
     * <p>
     * <b>Categories are NOT mutually exclusive</b> — an item can match multiple categories and their
     * aspects will stack. For example, "mossy_stone_brick_slab" would match stone, brick, slab, and
     * mossy/decorative categories simultaneously, accumulating terra, ignis, perditio, and ordo.
     * <b>类别之间不互斥</b> — 一个物品可以匹配多个类别，其要素会叠加。例如，"mossy_stone_brick_slab"
     * 会同时匹配石头、砖块、台阶和苔藓/装饰类别，累积 terra、ignis、perditio 和 ordo。
     *
     * <p>
     * <b>Exception:</b> The generic fluid fallback ("fluid"/"liquid") and the final catch-all ARE
     * mutually exclusive with prior matches — they only trigger if the {@code matched} flag is still false.
     * This prevents over-tagging specialized fluids that already matched a specific category (e.g., molten metal).
     * <b>例外：</b>通用流体兜底（"fluid"/"liquid"）和最终兜底与之前的匹配互斥 —
     * 它们仅在 matched 标志仍为 false 时触发。这防止了已匹配特定类别的特殊流体被过度标记
     * （例如，已匹配为熔融金属的流体）。
     *
     * <p>
     * Keyword lists are designed to cover vanilla MC, GregTech, Thaumcraft, Botania, Tinkers' Construct,
     * LOTR Mod, TerraFirmaCraft, and other common GTNH modpack mods.
     * 关键词列表设计覆盖原版 MC、GregTech、神秘时代、植物魔法、匠魂、LOTR 模组、TFC 及其他常见 GTNH 整合包模组。
     */
    static AspectList createGeneralFallback(final ItemStack stack) {
        final AspectList fb = new AspectList();

        String unloc = "";
        try {
            unloc = stack.getItem()
                .getUnlocalizedName()
                .toLowerCase();
            if (unloc.startsWith("tile.")) unloc = unloc.substring(5);
            else if (unloc.startsWith("item.")) unloc = unloc.substring(5);
        } catch (final Exception ignored) {}

        String reg = "";
        try {
            reg = Item.itemRegistry.getNameForObject(stack.getItem())
                .toLowerCase();
        } catch (final Exception ignored) {}

        final String n = unloc + " " + reg;
        boolean matched = false;

        // ===== Stone / Rock — EN / ZH / RU / JA / KO / DE / FR / ES =====
        if (AspectFallback.nameContains(
            n,
            "stone",
            "cobble",
            "rock",
            "basalt",
            "marble",
            "granite",
            "diorite",
            "andesite",
            "limestone",
            "slate",
            "shale",
            "gneiss",
            "quartzite",
            "rhyolite",
            "schist",
            "chalk",
            "dacite",
            "porphyry",
            "chert",
            "gabbro",
            "岩石",
            "石头",
            "圆石",
            "石砖",
            "花岗岩",
            "大理石",
            "石灰岩",
            "板岩",
            "安山岩",
            "闪长岩",
            "石英岩",
            "камень",
            "булыжник",
            "мрамор",
            "гранит",
            "сланец",
            "石",
            "岩",
            "大理石",
            "花崗岩",
            "砂岩",
            "돌",
            "바위",
            "대리석",
            "화강암",
            "조약돌",
            "Stein",
            "Fels",
            "Marmor",
            "Granit",
            "Pflasterstein",
            "pierre",
            "roche",
            "marbre",
            "granit",
            "pavé",
            "piedra",
            "roca",
            "mármol",
            "granito",
            "adoquín")) {
            AspectFallback.addFb(fb, "terra", 4);
            AspectFallback.addFb(fb, "perditio", 2);
            matched = true;
        }

        // ===== Bricks (fired stone) — EN / ZH / RU / JA / KO / DE / FR / ES =====
        if (AspectFallback.nameContains(
            n,
            "brick",
            "stonebrick",
            "stone_brick",
            "tiles",
            "paving",
            "砖",
            "石砖",
            "砖块",
            "铺路",
            "кирпич",
            "плитка",
            "レンガ",
            "石レンガ",
            "タイル",
            "벽돌",
            "타일",
            "Ziegel",
            "Fliese",
            "Pflaster",
            "brique",
            "tuile",
            "pavé",
            "ladrillo",
            "teja",
            "adoquín")) {
            AspectFallback.addFb(fb, "terra", 4);
            AspectFallback.addFb(fb, "ignis", 2);
            AspectFallback.addFb(fb, "perditio", 2);
            matched = true;
        }

        // ===== Slabs / Stairs / Walls / Fences — EN / ZH / RU / JA / KO / DE / FR / ES =====
        if (AspectFallback.nameContains(
            n,
            "slab",
            "stair",
            "wall",
            "fence",
            "pillar",
            "column",
            "post",
            "railing",
            "台阶",
            "楼梯",
            "墙",
            "栅栏",
            "柱",
            "плита",
            "ступень",
            "стена",
            "забор",
            "колонна",
            "ハーフブロック",
            "階段",
            "壁",
            "柵",
            "柱",
            "반블록",
            "계단",
            "벽",
            "울타리",
            "기둥",
            "Platte",
            "Treppe",
            "Wand",
            "Zaun",
            "Säule",
            "dalle",
            "escalier",
            "mur",
            "clôture",
            "pilier",
            "losa",
            "escalera",
            "muro",
            "valla",
            "pilar")) {
            AspectFallback.addFb(fb, "terra", 3);
            AspectFallback.addFb(fb, "perditio", 1);
            matched = true;
        }

        // ===== Smooth / Chiseled / Polished / Mossy (decorative stone variants) =====
        // Aesthetically processed stone. Gets ordo (order/craftsmanship) instead of perditio, reflecting
        // the human effort to shape and refine the raw material.
        // 经过美学加工的石材。获得 ordo（秩序/工艺）而非 perditio，反映人类塑造和精炼原材料的努力。
        if (AspectFallback
            .nameContains(n, "smooth", "polished", "chiseled", "carved", "mossy", "cracked", "cosmetic", "decorat")) {
            AspectFallback.addFb(fb, "terra", 3);
            AspectFallback.addFb(fb, "ordo", 2);
            matched = true;
        }

        // ===== Sand / Gravel / Dirt / Grass =====
        // Loose, unconsolidated earth materials. Higher perditio than stone because they crumble easily.
        // Includes grass (dirt with vegetation on top), farmland (tilled dirt), and mycelium.
        // 松散的、未固结的大地材料。比石头有更高的 perditio 因为它们容易碎裂。
        // 包括草方块（带植被的泥土）、耕地（翻耕的泥土）和菌丝。
        if (AspectFallback.nameContains(
            n,
            "sand",
            "gravel",
            "dirt",
            "soil",
            "mud",
            "podzol",
            "mycel",
            "grass",
            "farmland",
            "tilled",
            "coarse")) {
            AspectFallback.addFb(fb, "terra", 4);
            AspectFallback.addFb(fb, "perditio", 3);
            matched = true;
        }

        // ===== Clay / Terracotta =====
        // Wet earth that can be fired. Gets aqua (water content) + ignis (can be fired into bricks/terracotta).
        // 可烧制的湿土。获得 aqua（水分）+ ignis（可烧制为砖块/陶土）。
        if (AspectFallback.nameContains(n, "clay", "terracotta", "ceramic", "stained_clay", "hardened_clay")) {
            AspectFallback.addFb(fb, "terra", 4);
            AspectFallback.addFb(fb, "aqua", 2);
            AspectFallback.addFb(fb, "ignis", 1);
            matched = true;
        }

        // ===== Glass =====
        // Transparent/vitreous blocks. vitreus (crystal/glass) is the primary aspect.
        // 透明/玻璃质方块。vitreus（水晶/玻璃）是主要要素。
        if (AspectFallback.nameContains(n, "glass", "pane", "stained_glass")) {
            AspectFallback.addFb(fb, "vitreus", 4);
            AspectFallback.addFb(fb, "perditio", 1);
            matched = true;
        }

        // ===== Wood / Planks — EN / ZH / RU / JA / KO / DE / FR / ES =====
        if (AspectFallback.nameContains(
            n,
            "plank",
            "log",
            "wood",
            "timber",
            "lumber",
            "crate",
            "barrel",
            "shelf",
            "bookshelf",
            "木板",
            "原木",
            "木头",
            "木材",
            "桶",
            "书架",
            "доска",
            "бревно",
            "дерево",
            "бочка",
            "полка",
            "木材",
            "丸太",
            "樽",
            "本棚",
            "판자",
            "통나무",
            "나무",
            "통",
            "선반",
            "Brett",
            "Holz",
            "Fass",
            "Regal",
            "planche",
            "bûche",
            "bois",
            "tonneau",
            "étagère",
            "tablón",
            "tronco",
            "madera",
            "barril",
            "estante")) {
            AspectFallback.addFb(fb, "arbor", 4);
            matched = true;
        }

        // ===== Sapling / Seedling — EN / ZH / RU / JA / KO / DE / FR / ES =====
        if (AspectFallback.nameContains(
            n,
            "sapling",
            "treesapling",
            "tree_sapling",
            "sprout",
            "seedling",
            "树苗",
            "树苗",
            "саженец",
            "росток",
            "苗木",
            "芽",
            "묘목",
            "새싹",
            "Setzling",
            "Spross",
            "pousse",
            "plantule",
            "brote",
            "plántula")) {
            AspectFallback.addFb(fb, "arbor", 4);
            AspectFallback.addFb(fb, "herba", 2);
            matched = true;
        }

        // ===== Leaves / Flowers / Plants / 植物 =====
        // Organic plant matter. Gets herba (plant) + victus (life). Covers Botania mystical flowers,
        // crop items, and various mod-added flora (mushrooms, ferns, cacti, algae, etc.).
        // 有机植物物质。获得 herba（植物）+ victus（生命）。覆盖植物魔法神秘花朵、
        // 农作物及模组植物（蘑菇、蕨类、仙人掌、藻类等）。兼容模组命名（plant、vegetation、botanical 等）。
        if (AspectFallback.nameContains(
            n,
            "leaf",
            "leaves",
            "petal",
            "flower",
            "mystical",
            "herb",
            "vine",
            "bush",
            "shrub",
            "moss",
            "fern",
            "mushroom",
            "fungus",
            "cactus",
            "lily",
            "rose",
            "tulip",
            "daisy",
            "orchid",
            "poppy",
            "crop",
            "seed",
            "wheat",
            "hay",
            "bale",
            "straw",
            "melon",
            "pumpkin",
            "gourd",
            "sugarcane",
            "sugar_cane",
            "reed",
            "cocoa",
            "nether_wart",
            "netherwart",
            "plant",
            "vegetation",
            "botanical",
            "root",
            "stem",
            "bulb",
            "tuber",
            "rhizome",
            "weed",
            "algae",
            "kelp",
            "foliage",
            "frond",
            "pollen",
            "spore",
            "植物")) {
            AspectFallback.addFb(fb, "herba", 4);
            AspectFallback.addFb(fb, "victus", 2);
            matched = true;
        }

        // ===== Ice / Snow =====
        // Frozen water blocks. Gets gelum (cold) + aqua (water origin).
        // 冻结的水方块。获得 gelum（寒冷）+ aqua（水的来源）。
        if (AspectFallback.nameContains(n, "ice", "snow", "frost", "frozen", "packed_ice")) {
            AspectFallback.addFb(fb, "gelum", 4);
            AspectFallback.addFb(fb, "aqua", 2);
            matched = true;
        }

        // ===== Nether blocks =====
        // Blocks from the Nether dimension. Base: ignis (fire/heat) + terra (solid).
        // Sub-checks: soul blocks also get spiritus (spirit); glowstone also gets lux (light).
        // 来自下界维度的方块。基础：ignis（火焰/热量）+ terra（固体）。
        // 子检查：灵魂方块额外获得 spiritus（灵魂）；荧石额外获得 lux（光）。
        if (AspectFallback.nameContains(n, "nether", "netherrack", "soulsand", "soul_sand", "magma", "glowstone")) {
            AspectFallback.addFb(fb, "ignis", 4);
            AspectFallback.addFb(fb, "terra", 2);
            if (AspectFallback.nameContains(n, "soul")) AspectFallback.addFb(fb, "spiritus", 2);
            if (AspectFallback.nameContains(n, "glow")) AspectFallback.addFb(fb, "lux", 3);
            matched = true;
        }

        // ===== End blocks =====
        // Blocks from the End dimension. Gets alienis (alien/otherworldly) alongside terra.
        // 来自末地维度的方块。在 terra 基础上获得 alienis（异界/超凡）。
        if (AspectFallback.nameContains(n, "end_stone", "endstone", "purpur")) {
            AspectFallback.addFb(fb, "terra", 3);
            AspectFallback.addFb(fb, "alienis", 3);
            matched = true;
        }

        // ===== Weapon / Blade / Sword (incl. SlashBlade 拔刀剑、断刀) =====
        // Fallback only: items that already have TC aspects are skipped before derivation (see AspectScanner).
        // 仅兜底：已有要素的拔刀剑等会在扫描阶段被跳过，不会进入推导，此处只对尚无要素的刀剑类生效。
        // Swords, katanas, and blade-like items that may not extend ItemSword. telum (weapon) + instrumentum (tool).
        if (AspectFallback.nameContains(
            n,
            "blade",
            "sword",
            "刀",
            "剑",
            "slash",
            "katana",
            "slashblade",
            "断刀",
            "nodachi",
            "tachi",
            "saya")) {
            AspectFallback.addFb(fb, "telum", 4);
            AspectFallback.addFb(fb, "instrumentum", 3);
            AspectFallback.addFb(fb, "metallum", 2);
            matched = true;
        }

        // ===== Obsidian =====
        // Volcanic glass formed from lava+water. Gets tenebrae (darkness) due to its black color and
        // association with dark/nether portals, plus ignis from its volcanic origin.
        // 由岩浆+水形成的火山玻璃。获得 tenebrae（黑暗）因为其黑色和与暗黑/下界传送门的关联，
        // 加上 ignis 来自其火山起源。
        if (AspectFallback.nameContains(n, "obsidian")) {
            AspectFallback.addFb(fb, "terra", 4);
            AspectFallback.addFb(fb, "tenebrae", 4);
            AspectFallback.addFb(fb, "ignis", 2);
            matched = true;
        }

        // ===== Bedrock =====
        // Indestructible foundation block. Gets high terra + tenebrae (darkness of the deep) + perditio.
        // 不可破坏的基础方块。获得高 terra + tenebrae（深处的黑暗）+ perditio。
        if (AspectFallback.nameContains(n, "bedrock")) {
            AspectFallback.addFb(fb, "terra", 6);
            AspectFallback.addFb(fb, "tenebrae", 3);
            AspectFallback.addFb(fb, "perditio", 2);
            matched = true;
        }

        // ===== Sponge =====
        // Water-absorbing block. Gets aqua (water interaction) + vacuos (absorbs/empties).
        // 吸水方块。获得 aqua（水交互）+ vacuos（吸收/排空）。
        if (AspectFallback.nameContains(n, "sponge")) {
            AspectFallback.addFb(fb, "aqua", 4);
            AspectFallback.addFb(fb, "vacuos", 3);
            matched = true;
        }

        // ===== TNT / Explosives =====
        // Explosive blocks and items. Gets ignis (fire/detonation) + perditio (destruction/entropy).
        // 爆炸方块和物品。获得 ignis（火焰/引爆）+ perditio（破坏/熵）。
        if (AspectFallback.nameContains(n, "tnt", "explosive", "dynamite", "bomb", "grenade", "nuke", "itnt")) {
            AspectFallback.addFb(fb, "ignis", 4);
            AspectFallback.addFb(fb, "perditio", 4);
            AspectFallback.addFb(fb, "potentia", 2);
            matched = true;
        }

        // ===== Lamps / Light sources =====
        // Any light-emitting block or item. Gets lux (light) + potentia (power/energy to produce light).
        // 任何发光的方块或物品。获得 lux（光）+ potentia（产生光的能量/动力）。
        if (AspectFallback.nameContains(n, "lamp", "lantern", "light", "candle", "torch", "chandel", "illumin")) {
            AspectFallback.addFb(fb, "lux", 4);
            AspectFallback.addFb(fb, "potentia", 2);
            matched = true;
        }

        // ===== Metal ingots / plates / wires =====
        // Processed metal forms common in GT. Gets high metallum (6) since these are pure metal products.
        // Keywords cover GT's extensive material processing chain (ingot → plate → bolt → screw → etc.).
        // 常见于 GT 的加工金属形态。获得高 metallum（6）因为这些是纯金属产品。
        // 关键词覆盖 GT 的广泛材料加工链（锭 → 板 → 螺栓 → 螺丝 → 等等）。
        if (AspectFallback.nameContains(
            n,
            "ingot",
            "plate",
            "nugget",
            "compressed",
            "dense",
            "rod",
            "bolt",
            "screw",
            "ring",
            "spring",
            "foil",
            "casing",
            "frame")) {
            AspectFallback.addFb(fb, "metallum", 6);
            AspectFallback.addFb(fb, "terra", 2);
            matched = true;
        }

        // ===== Molten metal fluids =====
        // Liquid metals from smeltery (Tinkers') or GT. Gets metallum + ignis (heat) + aqua (liquid state).
        // 冶炼厂（匠魂）或 GT 的液态金属。获得 metallum + ignis（热量）+ aqua（液态）。
        if (AspectFallback.nameContains(n, "molten", "liquid_metal")) {
            AspectFallback.addFb(fb, "metallum", 4);
            AspectFallback.addFb(fb, "ignis", 3);
            AspectFallback.addFb(fb, "aqua", 1);
            matched = true;
        }

        // ===== Petroleum / Chemical fluids =====
        // Industrial chemical and petroleum products, primarily from GT's chemical processing.
        // Gets machina (machine/industry) + aqua (liquid) + ignis (flammable/combustible).
        // 工业化学品和石油产品，主要来自 GT 的化学加工。
        // 获得 machina（机械/工业）+ aqua（液态）+ ignis（可燃/可燃烧）。
        if (AspectFallback.nameContains(
            n,
            "oil",
            "fuel",
            "petroleum",
            "creosote",
            "diesel",
            "gasoline",
            "kerosene",
            "naphtha",
            "benzene",
            "propane",
            "butane",
            "ethanol",
            "methanol",
            "lubricant",
            "coolant",
            "solvent")) {
            AspectFallback.addFb(fb, "machina", 3);
            AspectFallback.addFb(fb, "aqua", 2);
            AspectFallback.addFb(fb, "ignis", 2);
            matched = true;
        }

        // ===== Acid / Chemical fluids =====
        // Corrosive, toxic, or reactive chemicals from GT. Gets venenum (poison) due to hazardous nature,
        // plus aqua (liquid) + potentia (reactive energy).
        // 来自 GT 的腐蚀性、有毒或反应性化学品。因其危险性质获得 venenum（毒素），
        // 加上 aqua（液态）+ potentia（反应能量）。
        if (AspectFallback.nameContains(
            n,
            "acid",
            "chlor",
            "sulfur",
            "nitro",
            "hydrogen",
            "oxygen",
            "nitrogen",
            "ammonia",
            "methane",
            "fluorine",
            "mercury")) {
            AspectFallback.addFb(fb, "venenum", 3);
            AspectFallback.addFb(fb, "aqua", 2);
            AspectFallback.addFb(fb, "potentia", 2);
            matched = true;
        }

        // ===== Magical fluids =====
        // Fluids with supernatural properties from Thaumcraft, Botania, and other magic mods.
        // Gets praecantatio (magic) as the dominant aspect + aqua + potentia.
        // 来自神秘时代、植物魔法和其他魔法模组的具有超自然属性的流体。
        // 获得 praecantatio（魔法）作为主要要素 + aqua + potentia。
        if (AspectFallback
            .nameContains(n, "essence", "mana", "flux", "taint", "death", "pure", "vis", "ender", "xpjuice")) {
            AspectFallback.addFb(fb, "praecantatio", 4);
            AspectFallback.addFb(fb, "aqua", 2);
            AspectFallback.addFb(fb, "potentia", 2);
            matched = true;
        }

        // ===== Natural / Biological fluids =====
        // Fluids of organic/biological origin. Gets aqua (liquid) + victus (life) + bestia (animal).
        // Covers blood, milk, honey, tree sap, slime, and similar natural substances.
        // 有机/生物来源的流体。获得 aqua（液态）+ victus（生命）+ bestia（动物）。
        // 覆盖血液、牛奶、蜂蜜、树液、史莱姆和类似的天然物质。
        if (AspectFallback
            .nameContains(n, "blood", "milk", "honey", "sap", "juice", "slime", "latex", "resin", "syrup", "soup")) {
            AspectFallback.addFb(fb, "aqua", 3);
            AspectFallback.addFb(fb, "victus", 3);
            AspectFallback.addFb(fb, "bestia", 1);
            matched = true;
        }

        // ===== Generic fluid fallback =====
        // Only triggers if NO other category matched (!matched). Prevents over-tagging specialized fluids
        // that already received specific aspects above (e.g., molten metal already has metallum+ignis+aqua).
        // 仅在没有其他类别匹配时触发（!matched）。防止已在上面获得特定要素的特殊流体被过度标记
        // （例如，熔融金属已经有了 metallum+ignis+aqua）。
        if (!matched && AspectFallback.nameContains(n, "fluid", "liquid")) {
            AspectFallback.addFb(fb, "aqua", 4);
            AspectFallback.addFb(fb, "permutatio", 2);
            matched = true;
        }

        // ===== Gems / 宝石 =====
        // Precious stones and crystals used as items (not just ore form). vitreus (crystal) + potentia (energy)
        // + lucrum (value). Covers vanilla and modded gems (ruby, sapphire, amethyst, etc.) for mod compatibility.
        // 宝石与贵重晶体（物品形态）。vitreus（水晶）+ potentia（能量）+ lucrum（价值）。覆盖原版与模组宝石以兼容模组。
        if (AspectFallback.nameContains(
            n,
            "gem",
            "gemstone",
            "jewel",
            "ruby",
            "emerald",
            "sapphire",
            "amethyst",
            "peridot",
            "topaz",
            "diamond",
            "jade",
            "opal",
            "garnet",
            "citrine",
            "aquamarine",
            "宝石")) {
            AspectFallback.addFb(fb, "vitreus", 4);
            AspectFallback.addFb(fb, "potentia", 2);
            AspectFallback.addFb(fb, "lucrum", 2);
            matched = true;
        }

        // ===== Ores / Crystals / Dusts / Quartz variants =====
        // OreDict is tried first in derivation; this is keyword fallback when OreDict has no donor.
        // 推导时优先用矿物辞典；此处为矿辞无 donor 时的关键词兜底。
        // 覆盖：下界石英矿石/下界石英、石英块/柱/楼梯/台阶/砖、平滑石英、錾制石英等（中英文名与注册名）。
        if (AspectFallback.nameContains(
            n,
            "ore",
            "crystal",
            "quartz",
            "石英",
            "石英块",
            "石英柱",
            "石英楼梯",
            "石英台阶",
            "石英砖",
            "平滑石英",
            "錾制石英",
            "下界石英",
            "smooth_quartz",
            "chiseled_quartz",
            "quartz_pillar",
            "quartz_stairs",
            "quartz_slab",
            "quartz_brick",
            "dust",
            "shard",
            "fragment")) {
            AspectFallback.addFb(fb, "vitreus", 4);
            AspectFallback.addFb(fb, "potentia", 2);
            AspectFallback.addFb(fb, "terra", 2);
            matched = true;
        }

        // ===== Raw material / 原料·材料 =====
        // Generic raw materials and resources. terra (earth/solid) + permutatio (transformation/raw state).
        // 通用原料与资源。terra（大地/固体）+ permutatio（转化/原始状态）。
        if (AspectFallback
            .nameContains(n, "raw", "material", "resource", "材料", "原料", "资源", "原材料", "ingredient", "component", "部件")) {
            AspectFallback.addFb(fb, "terra", 3);
            AspectFallback.addFb(fb, "permutatio", 2);
            matched = true;
        }

        // ===== Alloy / 合金 =====
        // Alloyed metals. metallum (metal) + ordo (order/combination) + potentia (process).
        // 合金。metallum（金属）+ ordo（秩序/组合）+ potentia（加工）。
        if (AspectFallback.nameContains(n, "alloy", "合金", "blend", "混合物", "mixture")) {
            AspectFallback.addFb(fb, "metallum", 4);
            AspectFallback.addFb(fb, "ordo", 2);
            AspectFallback.addFb(fb, "potentia", 1);
            matched = true;
        }

        // ===== Powder / 粉末·颗粒 =====
        // Fine solid powders and pellets (not only dust). perditio (entropy/fine) + terra or potentia.
        // 细粉末与颗粒。perditio（熵/细小）+ terra 或 potentia。
        if (AspectFallback.nameContains(n, "powder", "粉末", "pellet", "颗粒", "grit", "particle")) {
            AspectFallback.addFb(fb, "perditio", 3);
            AspectFallback.addFb(fb, "terra", 2);
            matched = true;
        }

        // ===== Slime (solid) / 粘液·史莱姆 =====
        // Solid slime items (slimeball, etc.). limus (slime) + victus (life).
        // 固体粘液物品。limus（粘液）+ victus（生命）。
        if (AspectFallback.nameContains(n, "slimeball", "slime_ball", "粘液", "史莱姆", "史莱姆球", "gel", "gel_capsule")) {
            AspectFallback.addFb(fb, "limus", 4);
            AspectFallback.addFb(fb, "victus", 2);
            matched = true;
        }

        // ===== Lapis / 青金石 =====
        // Lapis lazuli and blue mineral. sensus (color/beauty) + ordo (order/enchanting).
        // 青金石与蓝色矿物。sensus（色彩/美）+ ordo（秩序/附魔）。
        if (AspectFallback.nameContains(n, "lapis", "青金石", "lazuli")) {
            AspectFallback.addFb(fb, "sensus", 4);
            AspectFallback.addFb(fb, "ordo", 2);
            matched = true;
        }

        // ===== Scrap / Residue / 废料·残渣 =====
        // Industrial scrap and residues. perditio (waste/entropy) + metallum when metal-related.
        // 工业废料与残渣。perditio（废料/熵）+ 金属相关时 metallum。
        if (AspectFallback.nameContains(n, "scrap", "废料", "residue", "残渣", "slag", "dross", "byproduct", "副产品")) {
            AspectFallback.addFb(fb, "perditio", 4);
            AspectFallback.addFb(fb, "metallum", 2);
            matched = true;
        }

        // ===== Metal by name / 金属名 =====
        // Items whose name contains a metal name but may not match ingot/plate (e.g. raw_iron, copper_chunk).
        // metallum + terra. Covers common metals in EN and CN.
        // 名称含金属名的物品（如 raw_iron、铜锭）。metallum + terra。覆盖中英文常见金属。
        if (AspectFallback.nameContains(
            n,
            "iron",
            "铜",
            "copper",
            "锡",
            "tin",
            "铅",
            "lead",
            "银",
            "silver",
            "金",
            "gold",
            "青铜",
            "bronze",
            "钢",
            "steel",
            "zinc",
            "镍",
            "nickel",
            "铝",
            "aluminum",
            "aluminium",
            "bismuth",
            "锑",
            "antimony",
            "钨",
            "tungsten",
            "铬",
            "chromium",
            "钛",
            "titanium",
            "钼",
            "molybdenum",
            "镁",
            "magnesium",
            "锰",
            "manganese",
            "铁",
            "铂",
            "platinum",
            "铱",
            "iridium",
            "钯",
            "palladium",
            "铌",
            "niobium",
            "钽",
            "tantalum")) {
            AspectFallback.addFb(fb, "metallum", 4);
            AspectFallback.addFb(fb, "terra", 2);
            matched = true;
        }

        // ===== Compound (solid) / 化合物 =====
        // Solid chemical compounds. potentia (reactive) + ordo (structured) or venenum when toxic.
        // 固体化合物。potentia（反应性）+ ordo（结构）或有毒时 venenum。
        if (AspectFallback.nameContains(n, "compound", "化合物", "chemical", "化学", "reagent", "试剂")) {
            AspectFallback.addFb(fb, "potentia", 3);
            AspectFallback.addFb(fb, "ordo", 2);
            matched = true;
        }

        // ===== Wool / Cloth =====
        // Textile/fabric items. Gets pannus (cloth) + bestia (animal origin, since wool comes from sheep).
        // 纺织品/织物物品。获得 pannus（布料）+ bestia（动物来源，因为羊毛来自绵羊）。
        if (AspectFallback.nameContains(n, "wool", "carpet", "cloth", "fabric", "banner", "curtain")) {
            AspectFallback.addFb(fb, "pannus", 4);
            AspectFallback.addFb(fb, "bestia", 2);
            matched = true;
        }

        // ===== Machines / Tech =====
        // Industrial and technological items, heavily from GT/IC2/EnderIO. Gets machina + instrumentum.
        // Keywords span the full range from simple gears to complex processors and generators.
        // 工业和科技物品，主要来自 GT/IC2/EnderIO。获得 machina + instrumentum。
        // 关键词涵盖从简单齿轮到复杂处理器和发电机的全部范围。
        if (AspectFallback.nameContains(
            n,
            "machine",
            "circuit",
            "gear",
            "processor",
            "motor",
            "piston",
            "pump",
            "conveyor",
            "robot",
            "turbine",
            "generator",
            "engine",
            "battery")) {
            AspectFallback.addFb(fb, "machina", 4);
            AspectFallback.addFb(fb, "instrumentum", 3);
            matched = true;
        }

        // ===== Pipes / Cables =====
        // Transport infrastructure for fluids/items/energy. Gets metallum (material) + machina (tech)
        // + iter (travel/path) since their purpose is to move things between locations.
        // 流体/物品/能量的传输基础设施。获得 metallum（材料）+ machina（科技）
        // + iter（旅行/路径）因为它们的目的是在位置之间移动物质。
        if (AspectFallback.nameContains(n, "pipe", "cable", "wire", "conduit", "duct", "tube")) {
            AspectFallback.addFb(fb, "metallum", 3);
            AspectFallback.addFb(fb, "machina", 2);
            AspectFallback.addFb(fb, "iter", 2);
            matched = true;
        }

        // ===== Redstone =====
        // Redstone-powered logic and signal components. Gets machina + potentia (energy/power).
        // Covers vanilla redstone components and modded sensors/detectors.
        // 红石动力逻辑和信号组件。获得 machina + potentia（能量/动力）。
        // 覆盖原版红石组件和模组传感器/探测器。
        if (AspectFallback.nameContains(
            n,
            "redstone",
            "repeater",
            "comparator",
            "lever",
            "button",
            "pressure_plate",
            "detector",
            "sensor")) {
            AspectFallback.addFb(fb, "machina", 3);
            AspectFallback.addFb(fb, "potentia", 3);
            matched = true;
        }

        // ===== Doors / Trapdoors / Ladders =====
        // Passage and access blocks. Gets iter (travel/path) + machina (mechanism) since they
        // control player movement and access.
        // 通道和出入方块。获得 iter（旅行/路径）+ machina（机械），因为它们控制玩家移动和进出。
        if (AspectFallback.nameContains(n, "door", "trapdoor", "gate", "ladder", "hatch")) {
            AspectFallback.addFb(fb, "iter", 3);
            AspectFallback.addFb(fb, "machina", 2);
            matched = true;
        }

        // ===== Keys / 钥匙 =====
        // Keys and lock-picking items. Gets instrumentum (tool for opening) + metallum (metal) + ordo (order/lock).
        // 钥匙与开锁类物品。获得 instrumentum（开锁工具）+ metallum（金属）+ ordo（秩序/锁）。
        if (AspectFallback.nameContains(n, "key", "钥匙", "lockpick", "lock_pick")) {
            AspectFallback.addFb(fb, "instrumentum", 4);
            AspectFallback.addFb(fb, "metallum", 2);
            AspectFallback.addFb(fb, "ordo", 2);
            matched = true;
        }

        // ===== Potions / 药水 =====
        // Potions, vials, brews. Gets praecantatio (magic/effect) + aqua (liquid) + victus (life/effect on body).
        // 药水、小瓶、酿造物。获得 praecantatio（魔法/效果）+ aqua（液态）+ victus（生命/作用于身体）。
        if (AspectFallback.nameContains(
            n,
            "potion",
            "药水",
            "vial",
            "phial",
            "elixir",
            "brew",
            "splash",
            "lingering",
            "tipped_arrow",
            "药瓶",
            "瓶子")) {
            AspectFallback.addFb(fb, "praecantatio", 4);
            AspectFallback.addFb(fb, "aqua", 3);
            AspectFallback.addFb(fb, "victus", 2);
            matched = true;
        }

        // ===== Bottles / 瓶子 (empty or generic) =====
        // Glass containers for liquids. vitreus (glass) + vacuos (empty container).
        // 盛装液体的玻璃容器。vitreus（玻璃）+ vacuos（空容器）。
        if (AspectFallback.nameContains(n, "bottle", "瓶子", "flask", "vial", "vessel", "vessel_empty")) {
            AspectFallback.addFb(fb, "vitreus", 3);
            AspectFallback.addFb(fb, "vacuos", 2);
            matched = true;
        }

        // ===== Bow / Crossbow / 弓弩 =====
        // Ranged weapons. instrumentum (tool/weapon) + motus (motion/projectile).
        // 远程武器。instrumentum（工具/武器）+ motus（运动/弹射）。
        if (AspectFallback.nameContains(n, "bow", "弓", "crossbow", "弩", "arbalest")) {
            AspectFallback.addFb(fb, "instrumentum", 4);
            AspectFallback.addFb(fb, "motus", 3);
            matched = true;
        }

        // ===== Shield / 盾牌 =====
        // Defensive items. tutamen (protection) + metallum (metal/wood).
        // 防御物品。tutamen（防护）+ metallum（金属/木质）。
        if (AspectFallback.nameContains(n, "shield", "盾牌", "buckler")) {
            AspectFallback.addFb(fb, "tutamen", 5);
            AspectFallback.addFb(fb, "metallum", 2);
            matched = true;
        }

        // ===== Fishing rod / 钓鱼竿 =====
        // Fishing tools. instrumentum + aqua (fishing in water) + vinculum (line/binding).
        // 钓鱼工具。instrumentum + aqua（水中钓鱼）+ vinculum（线/束缚）。
        if (AspectFallback.nameContains(n, "fishing_rod", "fishingrod", "fishing", "钓鱼", "钓竿")) {
            AspectFallback.addFb(fb, "instrumentum", 3);
            AspectFallback.addFb(fb, "aqua", 2);
            AspectFallback.addFb(fb, "vinculum", 2);
            matched = true;
        }

        // ===== Flint / Fire starter / 打火石 =====
        // Fire-making items. ignis (fire) + instrumentum (tool).
        // 取火物品。ignis（火）+ instrumentum（工具）。
        if (AspectFallback.nameContains(n, "flint", "firestarter", "lighter", "打火石", "火石", "igniter")) {
            AspectFallback.addFb(fb, "ignis", 4);
            AspectFallback.addFb(fb, "instrumentum", 2);
            matched = true;
        }

        // ===== Clock / 钟 =====
        // Time-keeping items. ordo (order/time) + machina (mechanism).
        // 计时物品。ordo（秩序/时间）+ machina（机械）。
        if (AspectFallback.nameContains(n, "clock", "钟", "watch", "timepiece")) {
            AspectFallback.addFb(fb, "ordo", 3);
            AspectFallback.addFb(fb, "machina", 2);
            matched = true;
        }

        // ===== Music disc / 唱片 =====
        // Recorded sound. sensus (senses/sound) + spiritus (soul/music).
        // 唱片/录音。sensus（感官/声音）+ spiritus（灵魂/音乐）。
        if (AspectFallback.nameContains(n, "disc", "record", "music_disc", "唱片", "music_record")) {
            AspectFallback.addFb(fb, "sensus", 4);
            AspectFallback.addFb(fb, "spiritus", 2);
            matched = true;
        }

        // ===== Books / Tomes =====
        // Knowledge-containing items. Gets high cognitio (knowledge) + arbor (paper from wood)
        // + praecantatio (many modded books are magical in nature).
        // 包含知识的物品。获得高 cognitio（知识）+ arbor（纸来自木头）
        // + praecantatio（很多模组书籍本质上是魔法的）。
        if (AspectFallback
            .nameContains(n, "book", "tome", "lexicon", "codex", "guide", "manual", "scroll", "grimoire")) {
            AspectFallback.addFb(fb, "cognitio", 6);
            AspectFallback.addFb(fb, "arbor", 3);
            AspectFallback.addFb(fb, "praecantatio", 2);
            matched = true;
        }

        // ===== Magical items =====
        // Explicitly magical items from Thaumcraft and other magic mods. Gets praecantatio (magic)
        // + potentia (power) + auram (aura/magical energy).
        // 来自神秘时代和其他魔法模组的明确魔法物品。获得 praecantatio（魔法）
        // + potentia（力量）+ auram（灵气/魔法能量）。
        if (AspectFallback
            .nameContains(n, "wand", "staff", "amulet", "talisman", "rune", "sigil", "thaumic", "arcane", "magic")) {
            AspectFallback.addFb(fb, "praecantatio", 4);
            AspectFallback.addFb(fb, "potentia", 3);
            AspectFallback.addFb(fb, "auram", 2);
            matched = true;
        }

        // ===== Rails =====
        // Rail blocks for minecarts. Gets metallum (iron material) + iter (travel/path).
        // 矿车轨道方块。获得 metallum（铁材料）+ iter（旅行/路径）。
        if (AspectFallback.nameContains(n, "rail")) {
            AspectFallback.addFb(fb, "metallum", 3);
            AspectFallback.addFb(fb, "iter", 3);
            matched = true;
        }

        // ===== Transport / Movement items =====
        // Items that facilitate travel or movement. Gets iter (travel) + motus (motion).
        // Broad category: vehicles (boat, minecart), teleportation (ender pearl, portal),
        // mobility gear (jetpack, glider), projectiles (arrow), and even boots.
        // 促进旅行或移动的物品。获得 iter（旅行）+ motus（运动）。
        // 广泛类别：载具（船、矿车）、传送（末影珍珠、传送门）、
        // 移动装备（喷气背包、滑翔翼）、弹射物（箭矢），甚至靴子。
        if (AspectFallback.nameContains(
            n,
            "boat",
            "minecart",
            "cart",
            "saddle",
            "ender_pearl",
            "enderpearl",
            "eye_of_ender",
            "endereye",
            "compass",
            "teleport",
            "warp",
            "portal",
            "elevator",
            "jetpack",
            "parachute",
            "glider",
            "arrow",
            "boots")) {
            AspectFallback.addFb(fb, "iter", 4);
            AspectFallback.addFb(fb, "motus", 3);
            matched = true;
        }

        // ===== Dyes / Pigments =====
        // Color-producing items. Gets sensus (senses/perception) since dyes affect visual appearance.
        // 产生颜色的物品。获得 sensus（感知/感官）因为染料影响视觉外观。
        if (AspectFallback.nameContains(n, "dye", "ink", "pigment", "color", "stain")) {
            AspectFallback.addFb(fb, "sensus", 4);
            AspectFallback.addFb(fb, "aqua", 1);
            matched = true;
        }

        // ===== Eggs / Stars / Skulls =====
        // Creature-related items: spawn eggs, mob drops (skulls/heads), and nether stars.
        // Gets bestia (beast/creature) + spiritus (spirit/soul).
        // 与生物相关的物品：刷怪蛋、怪物掉落物（头颅）和下界之星。
        // 获得 bestia（野兽/生物）+ spiritus（灵魂/精神）。
        if (AspectFallback.nameContains(n, "egg", "star", "skull", "head")) {
            AspectFallback.addFb(fb, "bestia", 3);
            AspectFallback.addFb(fb, "spiritus", 3);
            matched = true;
        }

        // ===== Bones / Skeletal remains =====
        // Skeletal/death-related items. Gets mortuus (death) + corpus (body/flesh).
        // Covers bone meal, bone blocks, and similar remnants.
        // 骨骼/死亡相关物品。获得 mortuus（死亡）+ corpus（身体/肉身）。
        // 覆盖骨粉、骨块和类似遗骸。
        if (AspectFallback.nameContains(n, "bone", "skeleton", "fossil")) {
            AspectFallback.addFb(fb, "mortuus", 4);
            AspectFallback.addFb(fb, "corpus", 3);
            matched = true;
        }

        // ===== Leather / Hide / Pelts =====
        // Animal-derived skin/hide materials. Gets bestia (animal origin) + pannus (cloth/fabric).
        // 动物来源的皮革/兽皮材料。获得 bestia（动物来源）+ pannus（布料/织物）。
        if (AspectFallback.nameContains(n, "leather", "hide", "pelt", "rawhide")) {
            AspectFallback.addFb(fb, "bestia", 4);
            AspectFallback.addFb(fb, "pannus", 3);
            matched = true;
        }

        // ===== Feathers =====
        // Bird/creature feathers. Gets volatus (flight) + bestia (creature) + aer (air).
        // 鸟类/生物羽毛。获得 volatus（飞行）+ bestia（生物）+ aer（空气）。
        if (AspectFallback.nameContains(n, "feather", "plume", "quill")) {
            AspectFallback.addFb(fb, "volatus", 4);
            AspectFallback.addFb(fb, "bestia", 2);
            AspectFallback.addFb(fb, "aer", 2);
            matched = true;
        }

        // ===== Coal / Charcoal / Fuel solids =====
        // Solid combustible materials. Gets ignis (fire/burn) + potentia (stored energy).
        // 固体可燃材料。获得 ignis（火焰/燃烧）+ potentia（储存的能量）。
        if (AspectFallback.nameContains(n, "coal", "charcoal", "coke", "peat", "lignite")) {
            AspectFallback.addFb(fb, "ignis", 4);
            AspectFallback.addFb(fb, "potentia", 3);
            matched = true;
        }

        // ===== Containers / Storage =====
        // Storage blocks and items. Gets vacuos (void/empty space) + arbor (usually wood-based).
        // 存储方块和物品。获得 vacuos（虚空/空间）+ arbor（通常是木质的）。
        if (AspectFallback.nameContains(
            n,
            "chest",
            "hopper",
            "storage",
            "dispenser",
            "dropper",
            "drawer",
            "locker",
            "bin",
            "crate")) {
            AspectFallback.addFb(fb, "vacuos", 4);
            AspectFallback.addFb(fb, "arbor", 2);
            matched = true;
        }

        // ===== Workstations / Utility blocks =====
        // Crafting and processing stations. Gets fabrico (crafting) + instrumentum (tools/instruments).
        // Covers vanilla crafting table, anvil, furnace, and modded workbenches.
        // 合成和加工工作站。获得 fabrico（制作）+ instrumentum（工具/器具）。
        // 覆盖原版工作台、铁砧、熔炉，以及模组工作台。
        if (AspectFallback.nameContains(
            n,
            "anvil",
            "furnace",
            "workbench",
            "crafting",
            "smithing",
            "grindstone",
            "loom",
            "stonecutter",
            "enchant")) {
            AspectFallback.addFb(fb, "fabrico", 4);
            AspectFallback.addFb(fb, "instrumentum", 3);
            matched = true;
        }

        // ===== Concrete / Cement =====
        // Industrial construction material from GT and other mods. Gets terra + ordo (processed/ordered).
        // 来自 GT 和其他模组的工业建筑材料。获得 terra + ordo（加工/有序）。
        if (AspectFallback.nameContains(n, "concrete", "cement", "asphalt")) {
            AspectFallback.addFb(fb, "terra", 4);
            AspectFallback.addFb(fb, "ordo", 2);
            AspectFallback.addFb(fb, "perditio", 1);
            matched = true;
        }

        // ===== Cobweb / String =====
        // Spider-related materials and binding fibers. Gets vinculum (binding/trap) + bestia (spider origin).
        // 蜘蛛相关材料和捆绑纤维。获得 vinculum（束缚/陷阱）+ bestia（蜘蛛来源）。
        if (AspectFallback.nameContains(n, "cobweb", "web", "string", "thread", "fiber", "silk")) {
            AspectFallback.addFb(fb, "vinculum", 3);
            AspectFallback.addFb(fb, "bestia", 2);
            matched = true;
        }

        // ===== Paper / Maps =====
        // Paper products and cartography items. Gets cognitio (knowledge) + arbor (wood/paper origin).
        // 纸制品和地图物品。获得 cognitio（知识）+ arbor（木/纸来源）。
        if (AspectFallback.nameContains(n, "paper", "map", "cartograph", "blueprint")) {
            AspectFallback.addFb(fb, "cognitio", 3);
            AspectFallback.addFb(fb, "arbor", 2);
            matched = true;
        }

        // ===== Food ingredients / Sweeteners =====
        // Raw food ingredients and sweeteners that aren't directly edible. Gets fames + aqua.
        // 非直接食用的生食材和甜味剂。获得 fames + aqua。
        if (AspectFallback.nameContains(n, "sugar", "spice", "salt", "flour", "dough", "butter", "cheese", "cream")) {
            AspectFallback.addFb(fb, "fames", 3);
            AspectFallback.addFb(fb, "aqua", 2);
            matched = true;
        }

        // ===== Rubber / Plastic / Polymer =====
        // Synthetic/industrial flexible materials from GT/IC2. Gets limus (slime/flexibility) + machina.
        // 来自 GT/IC2 的合成/工业柔性材料。获得 limus（史莱姆/柔性）+ machina。
        if (AspectFallback.nameContains(
            n,
            "rubber",
            "plastic",
            "polymer",
            "silicone",
            "polyethylene",
            "ptfe",
            "polycarbonate",
            "epoxy")) {
            AspectFallback.addFb(fb, "limus", 3);
            AspectFallback.addFb(fb, "machina", 2);
            matched = true;
        }

        // User-defined keyword fallbacks from config/ThaumicAllAspect/keyword-fallback.cfg
        // 用户配置的关键词兜底
        FallbackConfig.applyKeywordFallbacks(n, fb);

        // ===== Unmatched: generic fallback =====
        if (!matched) {
            AspectFallback.addFb(fb, "ordo", 2);
            AspectFallback.addFb(fb, "permutatio", 2);
            AspectFallback.addFb(fb, "perditio", 1);
        }

        return fb;
    }

    /**
     * Checks if ANY of the given keywords appears in the combined name string (OR logic, not AND).
     * 检查给定的关键词中是否有任意一个出现在组合名称字符串中（OR 逻辑，非 AND）。
     *
     * <p>
     * Returns true on the first match — short-circuits for performance.
     * 在第一次匹配时返回 true — 为性能而短路求值。
     */
    private static boolean nameContains(final String combined, final String... keywords) {
        for (final String kw : keywords) {
            if (combined.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Safely adds an aspect to the fallback list by its tag name. Silently skips if the aspect
     * doesn't exist (i.e., the mod providing it isn't loaded), avoiding NullPointerException.
     * 通过标签名安全地向兜底列表添加要素。如果要素不存在（即提供它的模组未加载），则静默跳过，
     * 避免 NullPointerException。
     */
    private static void addFb(final AspectList fb, final String tag, final int amount) {
        final Aspect a = AspectUtils.getAspect(tag);
        if (null != a) fb.add(a, amount);
    }
}
