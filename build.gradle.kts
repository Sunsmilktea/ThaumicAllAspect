plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// 版本号从 version.txt 读取，需手动修改后构建
val versionFile = rootProject.file("version.txt")
val versionStr = if (versionFile.exists()) versionFile.readText().trim() else "1.0.0"
version = versionStr
extra["modVersion"] = versionStr