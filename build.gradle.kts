plugins {
    // GTNH 官方约定插件（必须，0.1.+ 会自动更新到最新兼容版）
    id("com.gtnewhorizons.gtnhconvention")
}

group = "com.sunmilktea.thaumicallaspect"           // 你的反向域名风格
version = project.findProperty("modVersion")?.toString() ?: "1.0.0-dev"
base.archivesName.set("ThaumicAllAspect")

// GTNH 必须的仓库（官方 nexus）
repositories {
    mavenCentral()
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/public/") }
}

// 编码统一 UTF-8
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}