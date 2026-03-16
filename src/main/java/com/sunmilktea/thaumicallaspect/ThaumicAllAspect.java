package com.sunmilktea.thaumicallaspect;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.io.File;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.config.Configuration;

import com.sunmilktea.thaumicallaspect.aspect.AspectUtils;
import com.sunmilktea.thaumicallaspect.aspect.EntityAspectHelper;
import com.sunmilktea.thaumicallaspect.aspect.ItemBlockAspectHelper;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.AspectList;

/**
 * Main entry point for the ThaumicAllAspect mod, annotated with {@link Mod @Mod}.
 * This mod automatically derives and assigns Thaumcraft aspects to every item, block,
 * fluid, and entity that lacks them.
 *
 * <p>
 * FML lifecycle hooks used:
 * <ul>
 * <li>{@link #preInit} — Logs version information via Forge logger during pre-initialization.</li>
 * <li>{@link #onLoadComplete} — Triggered by {@link FMLLoadCompleteEvent}, which fires
 * <b>after</b> all mods have finished init/postInit. At this point every item, block,
 * recipe, and OreDictionary entry from all mods is fully registered, making it the
 * earliest safe moment to perform an exhaustive aspect scan.</li>
 * </ul>
 *
 * <p>
 * Item/block scanning is delegated to {@link ItemBlockAspectHelper}, which acts as a
 * façade over {@link com.sunmilktea.thaumicallaspect.aspect.AspectScanner AspectScanner}.
 *
 * <p>
 * Entity scanning iterates {@link EntityList#stringToClassMapping}, filtering for
 * {@link EntityLivingBase} subclasses only. For each qualifying entity, aspects are
 * derived via {@link EntityAspectHelper#getOrGenerateForEntity} and registered with
 * {@link ThaumcraftApi#registerEntityTag}. Any entity IDs that fail derivation are
 * collected and logged as warnings after the scan completes.
 *
 * <p>
 * 此模组的主入口类，使用 {@link Mod @Mod} 注解标记。
 * 该模组自动为所有缺少神秘时代要素的物品、方块、流体和实体推导并分配要素。
 *
 * <p>
 * 使用的 FML 生命周期钩子：
 * <ul>
 * <li>{@link #preInit} — 在预初始化阶段通过 Forge 日志记录版本信息。</li>
 * <li>{@link #onLoadComplete} — 由 {@link FMLLoadCompleteEvent} 触发，该事件在所有模组
 * 完成 init/postInit 之后才触发。此时所有模组的物品、方块、配方及矿物辞典条目
 * 均已完全注册，是执行全面要素扫描的最早安全时机。</li>
 * </ul>
 *
 * <p>
 * 物品/方块扫描委托给 {@link ItemBlockAspectHelper}，它是
 * {@link com.sunmilktea.thaumicallaspect.aspect.AspectScanner AspectScanner} 的外观类。
 *
 * <p>
 * 实体扫描遍历 {@link EntityList#stringToClassMapping}，仅处理
 * {@link EntityLivingBase} 的子类。对每个符合条件的实体，通过
 * {@link EntityAspectHelper#getOrGenerateForEntity} 推导要素并使用
 * {@link ThaumcraftApi#registerEntityTag} 注册。推导失败的实体 ID
 * 会被收集起来，在扫描完成后以警告形式记录。
 */
@Mod(
    modid = ThaumicAllAspect.MODID,
    name = ThaumicAllAspect.NAME,
    version = Tags.VERSION,
    dependencies = "required-after:Thaumcraft;required-after:Baubles")
public class ThaumicAllAspect {

    public static final String MODID = "thaumicallaspect";
    public static final String NAME = "Thaumic All Aspect";

    /** 0=完整扫描日志, 1=仅摘要, 2=关闭。默认 0，与未加配置前的行为一致。 */
    public static int scanLogLevel = 0;
    /** 为 true 时跳过 AbyssalCraft 诊断转储。默认 false，与未加配置前的行为一致。 */
    public static boolean skipDiagnosticDumps = false;
    /** 是否在仅有 1 种要素的情况下，用关键词兜底补充更多要素种类。默认开启。 */
    public static boolean enrichSingleAspect = true;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File cfgFile = event.getSuggestedConfigurationFile();
        if (cfgFile != null) {
            Configuration cfg = new Configuration(cfgFile);
            cfg.load();
            // 默认值 0 / false 保持原有功能与日志输出不变
            scanLogLevel = cfg.getInt(
                "scanLogLevel",
                "performance",
                0,
                0,
                2,
                "0=full scan log, 1=summary only (faster), 2=off (fastest). Reduces disk I/O during load.");
            skipDiagnosticDumps = cfg.getBoolean(
                "skipDiagnosticDumps",
                "performance",
                false,
                "Skip AbyssalCraft API/RitualRegistry diagnostic dumps (faster load, disable when debugging).");
            enrichSingleAspect = cfg.getBoolean(
                "enrichSingleAspect",
                "balance",
                true,
                "When a derived item ends up with only ONE aspect type, enrich it with keyword fallback aspects. "
                    + "Keeps existing aspect types and only adds new ones from fallback.");
            float decay = cfg.getFloat(
                "recipeDecay",
                "balance",
                0.1f,
                0.05f,
                1.0f,
                "Global decay factor for derived aspects. 0.1 = 90% decay (keep 10%), 1.0 = no decay. "
                    + "Each derivation layer multiplies aspect amounts by this value, with a per-aspect minimum of 1.");
            AspectUtils.RECIPE_DECAY = decay;
            if (cfg.hasChanged()) cfg.save();
            ModFileLogger.setScanLogLevel(scanLogLevel);
        }
        ModFileLogger.info("[ThaumicAllAspect] " + tr("Pre-initialization complete, version") + " " + Tags.VERSION);
    }

    @EventHandler
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        ModFileLogger
            .info("[ThaumicAllAspect] " + tr("All mods loaded, starting full aspect scan for items/blocks/fluids"));
        ItemBlockAspectHelper.scanAndAssignAspects();

        ModFileLogger.info("[ThaumicAllAspect] " + tr("Starting entity aspect completion"));
        @SuppressWarnings("unchecked")
        Map<String, Class<? extends Entity>> map = EntityList.stringToClassMapping;
        for (Map.Entry<String, Class<? extends Entity>> e : map.entrySet()) {
            String id = e.getKey();
            Class<? extends Entity> cls = e.getValue();
            if (id == null || cls == null || !EntityLivingBase.class.isAssignableFrom(cls)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends EntityLivingBase> livingClass = (Class<? extends EntityLivingBase>) cls;
            AspectList aspects = EntityAspectHelper.getOrGenerateForEntity(livingClass, id);
            if (aspects != null && aspects.size() > 0) {
                ThaumcraftApi.registerEntityTag(id, aspects.copy());
            }
        }

        Set<String> failedEntityIds = EntityAspectHelper.getFailedEntityIdsSnapshot();
        if (!failedEntityIds.isEmpty()) {
            ModFileLogger.warn("[ThaumicAllAspect] " + tr("The following entities still have no aspects:"));
            for (String id : failedEntityIds) {
                ModFileLogger.warn("[ThaumicAllAspect] - " + id);
            }
        }

        ModFileLogger.info("[ThaumicAllAspect] " + tr("Initialization complete"));
    }
}
