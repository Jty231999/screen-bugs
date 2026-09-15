// 屏幕苍蝇 —— Gradle 项目配置
//
// 仓库统一在这里声明（不用 init 脚本注入），原因：
//   dependencyResolutionManagement 一旦在 settings 里声明了仓库，
//   Gradle 就禁止在别处（allprojects / init 脚本）重复声明，否则直接报错。
//
// 注意一个容易踩的坑：pluginManagement { } 是文件里最先求值的块，
// 它看不到本文件顶层定义的变量，也拿不到 uri() 这类方法。
// 所以镜像地址必须内联写进各个 repository 块里，不能抽成公共变量。
//
// 顺序说明：国内镜像在前，官方源兜底。直连 Google 只有 ~300KB/s，
// 镜像能到 2MB/s 以上。

import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/google/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/google/")
        google()
        mavenCentral()
    }
}

rootProject.name = "FlyPrank"
include(":app")
