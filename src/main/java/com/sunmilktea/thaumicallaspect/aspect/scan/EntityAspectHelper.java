package com.sunmilktea.thaumicallaspect.aspect.scan;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;

import com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils;
import com.sunmilktea.thaumicallaspect.logging.ModFileLogger;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

/**
 * Derives and assigns Thaumcraft aspects for living entities using a multi-layered strategy:
 *
 * <ol>
 * <li><b>Class-hierarchy inheritance</b> — Recursively walks up to the entity's superclass
 * (stopping at {@link EntityLivingBase}). Inherited aspects are applied with <b>90% decay</b>
 * (keep 10%, min 1) per level.</li>
 * <li><b>Type classification</b> — Mutually exclusive categories derived from class/interface
 * membership:
 * <ul>
 * <li>{@link EntityWaterMob} → aqua (water affinity)</li>
 * <li>{@link EntityAnimal} → terra (earth affinity)</li>
 * <li>{@link IBossDisplayData} → potentia + perditio (power &amp; chaos)</li>
 * <li>{@link IMob} → perditio + mortuus (chaos &amp; death)</li>
 * </ul>
 * </li>
 * <li><b>Class-name heuristics for drops</b> — A simplified best-effort approach that infers
 * likely drops from the entity's simple class name (e.g. "Cow" → fames). This is
 * necessary because we cannot instantiate arbitrary entities to query their actual loot
 * tables without risking crashes. Covers common vanilla mob archetypes.</li>
 * </ol>
 *
 * <p>
 * 为生物实体推导并分配神秘时代要素，采用多层策略：
 *
 * <ol>
 * <li><b>类继承链</b> — 递归向上遍历实体的父类（在 {@link EntityLivingBase} 处停止）。
 * 继承的要素按每层 <b>90% 衰减</b>（保留 10%，至少 1 点）。</li>
 * <li><b>类型分类</b> — 根据类/接口归属进行互斥分类：
 * <ul>
 * <li>{@link EntityWaterMob} → aqua（水属性）</li>
 * <li>{@link EntityAnimal} → terra（土属性）</li>
 * <li>{@link IBossDisplayData} → potentia + perditio（力量与混沌）</li>
 * <li>{@link IMob} → perditio + mortuus（混沌与死亡）</li>
 * </ul>
 * </li>
 * <li><b>类名启发式掉落推断</b> — 一种简化的尽力而为方法，根据实体的简单类名推断可能的
 * 掉落物（例如 "Cow" → fames 饥饿要素）。这样做是因为我们无法实例化任意实体来查询
 * 其真实战利品表，否则可能导致崩溃。覆盖了常见的原版生物原型。</li>
 * </ol>
 */
public class EntityAspectHelper {

    private static final Set<String> FAILED_ENTITY_IDS = new TreeSet<String>();

    /**
     * Generates an {@link AspectList} for the given entity class by applying three derivation
     * methods in order: {@link #addAspectsByInheritance inheritance}, {@link #addAspectsByType
     * type classification}, and {@link #addAspectsByDrops drop heuristics}.
     *
     * <p>
     * Non-monster entities (those not implementing {@link IMob}) receive a baseline of
     * {@code bestia >= 4} before any derivation, reflecting their living nature.
     *
     * <p>
     * If all three methods produce zero aspects, the entity ID is recorded in
     * {@link #FAILED_ENTITY_IDS} for post-scan reporting rather than throwing an exception,
     * ensuring one problematic entity cannot abort the entire scan.
     *
     * <p>
     * 为给定实体类生成 {@link AspectList}，按顺序应用三种推导方法：
     * {@link #addAspectsByInheritance 继承}、{@link #addAspectsByType 类型分类} 和
     * {@link #addAspectsByDrops 掉落物启发式}。
     *
     * <p>
     * 非怪物实体（未实现 {@link IMob} 的）在任何推导之前会获得 {@code bestia >= 4}
     * 的基准值，反映其生物本质。
     *
     * <p>
     * 如果三种方法均未产生任何要素，则将实体 ID 记录到 {@link #FAILED_ENTITY_IDS}
     * 供扫描后报告，而非抛出异常，确保单个有问题的实体不会中止整个扫描流程。
     *
     * @param entityClass the entity class to derive aspects for / 要推导要素的实体类
     * @param explicitId  the entity's string ID (from EntityList), or null to use class name /
     *                    实体的字符串 ID（来自 EntityList），为 null 时使用类名
     * @return the derived aspects, or null if derivation failed / 推导出的要素，失败时返回 null
     */
    public static AspectList getOrGenerateForEntity(final Class<? extends EntityLivingBase> entityClass,
        final String explicitId) {
        if (null == entityClass) {
            return null;
        }

        final String entityId = (null == explicitId || explicitId.isEmpty()) ? entityClass.getName() : explicitId;

        final AspectList aspects = new AspectList();

        // Non-monster living entities always get bestia >= 4
        if (!IMob.class.isAssignableFrom(entityClass)) {
            final Aspect bestia = Aspect.getAspect("bestia");
            if (null != bestia) {
                aspects.add(bestia, 4);
            }
        }

        EntityAspectHelper.addAspectsByInheritance(entityClass, aspects);
        EntityAspectHelper.addAspectsByType(entityClass, aspects);
        EntityAspectHelper.addAspectsByDrops(entityClass, aspects);

        if (0 == aspects.size()) {
            EntityAspectHelper.FAILED_ENTITY_IDS.add(entityId);
            ModFileLogger.debug(
                "[ThaumicAllAspect] " + tr("Failed to derive aspects for entity, recording failed ID:")
                    + " "
                    + entityId);
            return null;
        }

        return aspects;
    }

    /**
     * Recursively traverses the entity's superclass chain and inherits aspects with 90% decay (keep 10%, min 1).
     * 递归遍历实体的父类链并以 90% 衰减继承要素（保留 10%，至少 1 点）。
     */
    private static void addAspectsByInheritance(final Class<? extends EntityLivingBase> entityClass,
        final AspectList aspects) {
        final Class<?> superClass = entityClass.getSuperclass();
        if (null != superClass && EntityLivingBase.class.isAssignableFrom(superClass)) {
            @SuppressWarnings("unchecked")
            final Class<? extends EntityLivingBase> superLivingClass = (Class<? extends EntityLivingBase>) superClass;
            final AspectList superAspects = EntityAspectHelper.getOrGenerateForEntity(superLivingClass, null);
            if (AspectUtils.hasPositiveAspectAmount(superAspects)) {
                Aspect[] superAspArr = superAspects.getAspects();
                if (null == superAspArr) superAspArr = new Aspect[0];
                for (final Aspect aspect : superAspArr) {
                    if (null != aspect) {
                        final int amount = superAspects.getAmount(aspect);
                        final int scaledAmount = Math.max(1, Math.round(amount * 0.1f));
                        aspects.add(aspect, scaledAmount);
                    }
                }
            }
        }
    }

    /**
     * Assigns aspects based on mutually exclusive type classification.
     *
     * <p>
     * The classification is checked in priority order — only the first matching branch
     * applies:
     * <ol>
     * <li>Water mob ({@link EntityWaterMob}) → {@code aqua >= 2}</li>
     * <li>Animal ({@link EntityAnimal}) → {@code terra >= 2}</li>
     * <li>Boss ({@link IBossDisplayData}) → {@code potentia >= 4, perditio >= 2}</li>
     * <li>Monster ({@link IMob}) → {@code perditio >= 3, mortuus >= 2}</li>
     * </ol>
     *
     * <p>
     * Minimum threshold checks (e.g. {@code aspects.getAmount(water) < 2}) ensure that
     * aspects already contributed by inheritance are not overwritten with a smaller value.
     *
     * <p>
     * 根据互斥类型分类分配要素。
     *
     * <p>
     * 按优先级顺序检查分类——仅第一个匹配的分支生效：
     * <ol>
     * <li>水生生物 ({@link EntityWaterMob}) → {@code aqua >= 2}</li>
     * <li>动物 ({@link EntityAnimal}) → {@code terra >= 2}</li>
     * <li>Boss ({@link IBossDisplayData}) → {@code potentia >= 4, perditio >= 2}</li>
     * <li>怪物 ({@link IMob}) → {@code perditio >= 3, mortuus >= 2}</li>
     * </ol>
     *
     * <p>
     * 最小阈值检查（如 {@code aspects.getAmount(water) < 2}）确保已由继承贡献的
     * 要素不会被更小的值覆盖。
     */
    private static void addAspectsByType(final Class<? extends EntityLivingBase> entityClass,
        final AspectList aspects) {
        if (EntityWaterMob.class.isAssignableFrom(entityClass)) {
            final Aspect water = Aspect.getAspect("aqua");
            if (null != water && 2 > aspects.getAmount(water)) {
                aspects.add(water, 2);
            }
        } else if (EntityAnimal.class.isAssignableFrom(entityClass)) {
            final Aspect earth = Aspect.getAspect("terra");
            if (null != earth && 2 > aspects.getAmount(earth)) {
                aspects.add(earth, 2);
            }
        } else if (IBossDisplayData.class.isAssignableFrom(entityClass)) {
            final Aspect power = Aspect.getAspect("potentia");
            if (null != power && 4 > aspects.getAmount(power)) {
                aspects.add(power, 4);
            }
            final Aspect chaos = Aspect.getAspect("perditio");
            if (null != chaos && 2 > aspects.getAmount(chaos)) {
                aspects.add(chaos, 2);
            }
        } else if (IMob.class.isAssignableFrom(entityClass)) {
            final Aspect chaos = Aspect.getAspect("perditio");
            if (null != chaos && 3 > aspects.getAmount(chaos)) {
                aspects.add(chaos, 3);
            }
            final Aspect death = Aspect.getAspect("mortuus");
            if (null != death && 2 > aspects.getAmount(death)) {
                aspects.add(death, 2);
            }
        }
    }

    /**
     * Infers aspects from likely drops using a simplified class-name keyword heuristic.
     *
     * <p>
     * We cannot safely instantiate arbitrary entity classes to call their loot-table
     * methods (many require a valid World or specific constructor arguments), so instead
     * we match the entity's {@link Class#getSimpleName() simple class name} against known
     * keywords corresponding to common vanilla mob archetypes:
     * <ul>
     * <li>cow / sheep / pig / chicken → {@code fames} (food-dropping livestock)</li>
     * <li>skeleton / zombie → {@code mortuus} (undead mobs)</li>
     * <li>spider / creeper → {@code venenum} (poison/toxin-associated mobs)</li>
     * </ul>
     *
     * <p>
     * 无法安全地实例化任意实体类来调用其战利品表方法（许多需要有效的 World 或
     * 特定构造函数参数），因此改为将实体的 {@link Class#getSimpleName() 简单类名}
     * 与已知关键词进行匹配，覆盖常见的原版生物原型：
     * <ul>
     * <li>cow / sheep / pig / chicken → {@code fames}（产出食物的家畜）</li>
     * <li>skeleton / zombie → {@code mortuus}（亡灵生物）</li>
     * <li>spider / creeper → {@code venenum}（毒素/爆炸相关生物）</li>
     * </ul>
     */
    private static void addAspectsByDrops(final Class<? extends EntityLivingBase> entityClass,
        final AspectList aspects) {
        final String className = entityClass.getSimpleName()
            .toLowerCase();

        if (className.contains("cow") || className.contains("sheep")
            || className.contains("pig")
            || className.contains("chicken")) {
            final Aspect food = Aspect.getAspect("fames");
            if (null != food && 2 > aspects.getAmount(food)) {
                aspects.add(food, 2);
            }
        } else if (className.contains("skeleton") || className.contains("zombie")) {
            final Aspect death = Aspect.getAspect("mortuus");
            if (null != death && 3 > aspects.getAmount(death)) {
                aspects.add(death, 3);
            }
        } else if (className.contains("spider") || className.contains("creeper")) {
            final Aspect poison = Aspect.getAspect("venenum");
            if (null != poison && 2 > aspects.getAmount(poison)) {
                aspects.add(poison, 2);
            }
        }
    }

    public static Set<String> getFailedEntityIdsSnapshot() {
        return Collections.unmodifiableSet(EntityAspectHelper.FAILED_ENTITY_IDS);
    }
}
