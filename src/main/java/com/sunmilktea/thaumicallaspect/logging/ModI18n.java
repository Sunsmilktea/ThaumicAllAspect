package com.sunmilktea.thaumicallaspect.logging;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Simple internationalization utility for log output.
 * Detects system locale at class load time; if Chinese, translates log strings.
 * English is the default/fallback language.
 *
 * 简单的日志国际化工具。
 * 在类加载时检测系统语言环境；如果是中文则翻译日志字符串。
 * 英文为默认/回退语言。
 */
public final class ModI18n {

    private static final boolean USE_ZH;
    private static final Map<String, String> ZH = new HashMap<>();

    static {
        // Locale detection strategy: uses Java's Locale.getDefault().getLanguage() which
        // reflects the OS language setting. Chinese Windows users (language = "zh") get
        // Chinese log output; everyone else gets English. This approach works on both
        // client and dedicated server without referencing any Minecraft-specific classes.
        //
        // 语言检测策略：使用 Java 的 Locale.getDefault().getLanguage()，反映操作系统
        // 的语言设置。中文 Windows 用户（language = "zh"）获得中文日志输出；其他用户
        // 获得英文。此方法在客户端和独立服务器上均可工作，无需引用任何 Minecraft 类。
        String lang = Locale.getDefault()
            .getLanguage();
        USE_ZH = "zh".equalsIgnoreCase(lang);

        // Derivation path labels / 推导路径标签
        t("cache hit", "缓存命中");
        t("unknown", "未知");
        t("TC registered", "TC已注册");
        t("depth overflow fallback", "深度溢出兜底");
        t("OreDict equivalent", "矿辞等价");
        t("crafting recipe", "合成配方");
        t("smelting recipe", "烧炼配方");
        t("TC recipe", "TC配方");
        t("same-item meta inheritance", "同物品meta继承");
        t("TC generator", "TC生成器");
        t("type derivation", "类型推导");
        t("keyword fallback", "关键词兜底");

        // Log section prefixes / 日志区段前缀
        t("[Register]", "[注册]");
        t("[Retry pending]", "[待重试]");
        t("[Crash]", "[崩溃]");
        t("[Error]", "[错误]");
        t("[Scan]", "[扫描]");

        t("[Verify]", "[验证]");
        t("User-defined verification", "用户自定义验证");
        t("[Stats]", "[统计]");
        t("[Failures]", "[失败]");
        t("[Retry]", "[重试]");
        t("[Index]", "[索引]");
        t("[Fluid register]", "[流体注册]");
        t("[Fluid crash]", "[流体崩溃]");
        t("[Pass", "[第");

        // Descriptive messages / 描述性消息
        t("no aspects derived in pass 1", "第一轮未推导出要素");
        t("failed to get metas:", "获取meta失败:");
        t("No new registrations this pass, stopping", "本轮无新注册，停止");
        t("Starting full scan", "开始完整扫描");
        t("Full scan complete, total time", "完整扫描完成，总耗时");
        t("not found in registry!", "未在注册表中找到!");
        t("empty/null", "空/null");
        t("not in cache", "不在缓存中");
        t("with aspects", "有要素");
        t("Full scan summary", "完整扫描总结");
        t("Retrying", "重试");
        t("cache entries", "缓存条目");
        t("register", "注册");
        t("crash", "崩溃");

        // Data labels / 数据标签
        t("items", "物品");
        t("skipped", "跳过");
        t("scan skipped", "扫描已跳过");
        t("registered", "已注册");
        t("failed", "失败");
        t("mods", "模组");
        t("items/blocks from", "物品/方块，来自");
        t("Done:", "完成:");
        t("still failed", "仍失败");
        t("input", "输入");

        // Stats labels / 统计标签
        t("Total items/blocks:", "物品/方块总数:");
        t("Already had aspects (skipped):", "已有要素(跳过):");
        t("Newly registered:", "新注册:");
        t("Derivation failed (no aspects):", "推导失败(无要素):");
        t("Cache entries:", "缓存条目:");
        t("Fully failed items (no meta has aspects):", "完全失败物品(无meta有要素):");
        t("The following items/blocks/fluids still have no aspects:", "以下物品/方块/流体仍无要素:");
        t("Failed item/block/fluid scan IDs:", "失败物品/方块/流体扫描ID:");
        t("Fluid scan: total", "流体扫描: 总计");
        t("Registry total:", "注册表总计:");
        t("Mod-specific recipes registered:", "模组专属配方注册:");
        t("[Mod recipes]", "[模组配方]");
        t("Registered", "已注册");
        t("items from mod-specific recipes in", "物品通过模组专属配方，耗时");
        t("detected, scanning", "已检测到，正在扫描");
        t("Found", "找到");
        t("Necronomicon ritual objects", "死灵之书仪式对象");
        t("no new items registered", "无新物品注册");
        t("total", "总计");
        t("new items, retrying for dependencies...", "个新物品，重试依赖链...");
        t("[Post-scan]", "[扫描后验证]");
        t("Recovered", "恢复");
        t("items from failure list (TC lazy generation)", "个物品从失败列表中（TC延迟生成）");
        t("Meta inheritance sweep: fixed", "Meta继承补扫: 修复");
        t("item metas", "个物品meta");

        // Lifecycle messages / 生命周期消息
        t("Pre-initialization complete, version", "预初始化完成，版本");
        t("All mods loaded, starting full aspect scan for items/blocks/fluids", "所有模组加载完毕，开始物品/方块/流体全面要素扫描");
        t("Starting entity aspect completion", "开始实体要素补全");
        t("The following entities still have no aspects:", "以下实体仍无要素:");
        t("Initialization complete", "初始化完成");
        t("Failed to derive aspects for entity, recording failed ID:", "未能推导实体要素，记录失败ID:");

        // File logger messages / 文件日志消息
        t("Aspect not found:", "找不到要素:");
        t("Failed to create scan log file:", "创建扫描日志文件失败:");
        t("Scan details written to:", "扫描详情已写入:");
        t("Aspect cache written to:", "要素缓存已写入:");
        t("Error writing cache file", "写入缓存文件错误");
        t("Failed to create output directory:", "创建输出目录失败:");
        t("Error writing failure IDs file", "写入失败ID文件错误");

        // Index labels / 索引标签
        t("OreDictionary:", "矿物辞典:");
        t("Crafting recipes:", "合成配方:");
        t("recipes,", "配方,");
        t("output items", "输出物品");
        t("Furnace recipes:", "烧炼配方:");
        t("entries,", "条目,");
        t("Thaumcraft recipes:", "神秘时代配方:");
    }

    private ModI18n() {}

    private static void t(String en, String zh) {
        ZH.put(en, zh);
    }

    /**
     * Translates a string based on system locale.
     * Returns Chinese translation if locale is zh, otherwise returns the English input.
     *
     * 根据系统语言环境翻译字符串。
     * 如果语言环境为中文则返回中文翻译，否则返回英文原文。
     */
    public static String tr(String en) {
        if (USE_ZH) {
            String zh = ZH.get(en);
            return zh != null ? zh : en;
        }
        return en;
    }

    /** Returns true if the current locale is Chinese. / 当前语言环境是否为中文。 */
    public static boolean isChinese() {
        return USE_ZH;
    }
}
