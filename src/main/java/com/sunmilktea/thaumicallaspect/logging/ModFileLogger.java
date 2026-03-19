package com.sunmilktea.thaumicallaspect.logging;

import static com.sunmilktea.thaumicallaspect.logging.ModI18n.tr;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

/**
 * Centralized logging facility with three output channels:
 *
 * <ol>
 * <li><b>Forge logger (console)</b> — Standard Log4j logger named "ThaumicAllAspect",
 * accessible via {@link #info}, {@link #warn}, {@link #debug}, {@link #error}.
 * Output appears in the game console and Forge's latest.log.</li>
 * <li><b>Scan log file</b> — A dedicated file at {@code logs/ThaumicAllAspect-scan.log},
 * UTF-8 encoded to properly handle international item/mod names. Written exclusively
 * by {@link #scan(String)} for high-volume per-item detail that would flood the console.
 * Managed via {@link #beginScanLog()} / {@link #endScanLog()} lifecycle.</li>
 * <li><b>Failure &amp; cache files</b> — Additional output files for post-scan analysis:
 * {@link #writeCacheFile} dumps all cached aspects, {@link #appendFailureIds} records
 * items/entities that could not be assigned aspects.</li>
 * </ol>
 *
 * <p>
 * 集中式日志工具，提供三个输出通道：
 *
 * <ol>
 * <li><b>Forge 日志（控制台）</b> — 名为 "ThaumicAllAspect" 的标准 Log4j 日志器，
 * 通过 {@link #info}、{@link #warn}、{@link #debug}、{@link #error} 访问。
 * 输出显示在游戏控制台和 Forge 的 latest.log 中。</li>
 * <li><b>扫描日志文件</b> — 位于 {@code logs/ThaumicAllAspect-scan.log} 的专用文件，
 * 使用 UTF-8 编码以正确处理国际化的物品/模组名称。仅由 {@link #scan(String)}
 * 写入，用于记录会淹没控制台的大量逐物品详细信息。
 * 通过 {@link #beginScanLog()} / {@link #endScanLog()} 管理生命周期。</li>
 * <li><b>失败与缓存文件</b> — 用于扫描后分析的额外输出文件：
 * {@link #writeCacheFile} 导出所有缓存的要素，{@link #appendFailureIds} 记录
 * 无法分配要素的物品/实体。</li>
 * </ol>
 */
public final class ModFileLogger {

    private static final Logger LOGGER = LogManager.getLogger("ThaumicAllAspect");

    private static final File SCAN_LOG_FILE;
    private static BufferedWriter scanWriter;
    /** 0=完整, 1=仅摘要, 2=关闭。默认 0，与优化前行为一致；由 ThaumicAllAspect.preInit 从配置覆盖。 */
    private static int scanLogLevel = 0;

    static {
        File dir = new File("logs");
        if (!dir.exists()) dir.mkdirs();
        SCAN_LOG_FILE = new File(dir, "ThaumicAllAspect-scan.log");
    }

    private ModFileLogger() {}

    public static void setScanLogLevel(int level) {
        scanLogLevel = level;
    }

    public static void info(String msg) {
        LOGGER.info(msg);
    }

    public static void warn(String msg) {
        LOGGER.warn(msg);
    }

    public static void debug(String msg) {
        LOGGER.debug(msg);
    }

    public static void error(String msg, Throwable t) {
        LOGGER.error(msg, t);
    }

    /**
     * Opens the scan log file for writing. Must be called before any {@link #scan} calls.
     * The file is overwritten (not appended) on each scan run, and a timestamp header is
     * written as the first line for traceability.
     *
     * <p>
     * 打开扫描日志文件以供写入。必须在任何 {@link #scan} 调用之前调用。
     * 每次扫描运行时文件会被覆盖（而非追加），并写入时间戳头作为第一行以便追溯。
     */
    public static void beginScanLog() {
        if (scanLogLevel >= 2) return;
        try {
            scanWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(SCAN_LOG_FILE, false), StandardCharsets.UTF_8));
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            scanWriter.write("========== ThaumicAllAspect Scan Log " + ts + " ==========");
            scanWriter.newLine();
            scanWriter.newLine();
        } catch (IOException e) {
            LOGGER.warn(
                "[ThaumicAllAspect] " + tr("Failed to create scan log file:") + " " + SCAN_LOG_FILE.getAbsolutePath(),
                e);
        }
    }

    /**
     * Writes a message to the scan log file only (not the console).
     * When scanLogLevel >= 1 (summary or off) this is a no-op to reduce I/O and speed up load.
     *
     * <p>
     * 仅向扫描日志文件写入消息（不输出到控制台）。
     * 当 scanLogLevel >= 1 时不写入，以减少 I/O 加快加载。
     */
    public static void scan(String msg) {
        if (scanLogLevel >= 1 || scanWriter == null) return;
        try {
            scanWriter.write(msg);
            scanWriter.newLine();
        } catch (IOException ignored) {}
    }

    /**
     * Writes a summary line to the scan log (phase headers, stats, failures).
     * Only skipped when scanLogLevel >= 2 (log off). Use for low-volume summary output.
     *
     * <p>
     * 将摘要行写入扫描日志（阶段头、统计、失败列表）。仅当 scanLogLevel >= 2 时不写入。
     */
    public static void scanSummary(String msg) {
        if (scanLogLevel >= 2 || scanWriter == null) return;
        try {
            scanWriter.write(msg);
            scanWriter.newLine();
        } catch (IOException ignored) {}
    }

    /**
     * Flushes and closes the scan log file, completing the scan log lifecycle.
     * Logs the output file path to the console so users know where to find the detailed log.
     * Safe to call even if {@link #beginScanLog()} was not called (no-op in that case).
     *
     * <p>
     * 刷新并关闭扫描日志文件，完成扫描日志生命周期。
     * 将输出文件路径记录到控制台，以便用户知道详细日志的位置。
     * 即使未调用 {@link #beginScanLog()} 也可安全调用（此时为空操作）。
     */
    public static void endScanLog() {
        if (scanWriter != null) {
            try {
                scanWriter.flush();
                scanWriter.close();
            } catch (IOException ignored) {}
            scanWriter = null;
            LOGGER.info("[ThaumicAllAspect] " + tr("Scan details written to:") + " " + SCAN_LOG_FILE.getAbsolutePath());
        }
    }

    /**
     * Dumps all cached aspect entries to {@code logs/ThaumicAllAspect-cache.cfg}, sorted
     * alphabetically by item key for easy browsing and diffing between runs.
     *
     * <p>
     * Output format per line: {@code ItemID@meta = aspect1=amount, aspect2=amount, ...}
     *
     * <p>
     * 将所有缓存的要素条目导出到 {@code logs/ThaumicAllAspect-cache.cfg}，
     * 按物品键名字母排序，便于浏览和在不同运行之间进行对比。
     *
     * <p>
     * 每行输出格式：{@code ItemID@meta = aspect1=amount, aspect2=amount, ...}
     */
    public static void writeCacheFile(Map<String, AspectList> cache) {
        File dir = new File("logs");
        if (!dir.exists()) dir.mkdirs();
        writeCacheFile(cache, new File(dir, "ThaumicAllAspect-cache.cfg"), true);
    }

    /**
     * Writes the same aspect cache to a given file (e.g. config for server copy).
     * 将同一份要素缓存写入指定文件（如 config 目录供服务器拷贝）。
     */
    public static void writeCacheFile(Map<String, AspectList> cache, File file, boolean logSuccess) {
        if (file == null) return;
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8));
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            writer.write("# ThaumicAllAspect Aspect Cache");
            writer.newLine();
            writer.write("# Generated: " + ts);
            writer.newLine();
            writer.write("# Entries: " + cache.size());
            writer.newLine();
            writer.write("# Format: ItemID@meta = aspect1=amount, aspect2=amount, ...");
            writer.newLine();
            writer.newLine();

            TreeMap<String, AspectList> sorted = new TreeMap<>(cache);
            for (Map.Entry<String, AspectList> entry : sorted.entrySet()) {
                String key = entry.getKey();
                AspectList al = entry.getValue();
                if (al == null || al.size() == 0) continue;
                // Normalize before writing: never persist 0/negative amounts to the cache file.
                al = com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils.ensureMinOnePerAspect(al);
                if (al == null || al.size() == 0) continue;

                StringBuilder sb = new StringBuilder();
                sb.append(key)
                    .append(" = ");
                boolean first = true;
                Aspect[] cacheAspects = al.getAspects();
                if (cacheAspects == null) cacheAspects = new Aspect[0];
                for (Aspect a : cacheAspects) {
                    if (a == null) continue;
                    if (!first) sb.append(", ");
                    sb.append(a.getTag())
                        .append("=")
                        .append(al.getAmount(a));
                    first = false;
                }
                writer.write(sb.toString());
                writer.newLine();
            }

            if (logSuccess) {
                LOGGER.info(
                    "[ThaumicAllAspect] " + tr("Aspect cache written to:")
                        + " "
                        + file.getAbsolutePath()
                        + " ("
                        + sorted.size()
                        + " entries)");
            }
        } catch (IOException e) {
            LOGGER.warn("[ThaumicAllAspect] " + tr("Error writing cache file"), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Writes failed item/entity IDs to {@code logs/ThaumicAllAspect-failures.txt} for easy
     * post-scan analysis. The file is overwritten each run so it always reflects the latest
     * scan's failures. A header line (e.g. describing the failure category) is written first,
     * followed by each ID prefixed with "- ".
     *
     * <p>
     * 将失败的物品/实体 ID 写入 {@code logs/ThaumicAllAspect-failures.txt}，
     * 便于扫描后分析。文件每次运行时被覆盖，因此始终反映最新一次扫描的失败情况。
     * 首先写入一行标题（如描述失败类别），随后每个 ID 以 "- " 为前缀逐行写入。
     */
    public static void appendFailureIds(String header, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        File dir = new File("logs");
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warn("[ThaumicAllAspect] " + tr("Failed to create output directory:") + " " + dir.getAbsolutePath());
            return;
        }
        File file = new File(dir, "ThaumicAllAspect-failures.txt");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8));
            writer.write(header);
            writer.newLine();
            for (String id : ids) {
                writer.write("- " + id);
                writer.newLine();
            }
        } catch (IOException e) {
            LOGGER.warn("[ThaumicAllAspect] " + tr("Error writing failure IDs file"), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
