import com.android.ide.common.signing.KeystoreHelper
import com.v7878.zygisk.gradle.ZygoteLoader
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.PrintStream
import java.util.Locale
import kotlin.io.path.Path

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.com.github.aerathstuff.zygoteloader)
}

val appPackageName: String by rootProject.extra

android {
    namespace = "$appPackageName.zygote"

    defaultConfig {
        applicationId = namespace
    }

    sourceSets {
        getByName("main") {
            java {
                srcDirs(Path(rootDir.path, "external", "AndroidVMTools", "src", "main", "java"))
            }
        }
    }
}

tasks.clean {
    for (item in arrayOf("debug", "release")) {
        delete(File(android.sourceSets[item].assets.srcDirs.first(), "manager.apk"))
    }
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val variantLowered = variant.name.lowercase(Locale.ROOT)

        val outSrcDir = layout.buildDirectory.dir("generated/source/signInfo/${variantLowered}")
        val outSrc = outSrcDir.get().file("org/apkesu/app/zygote/Magic.java")
        val signInfoTask = tasks.register("generate${variantCapped}SignInfo") {
            description = "Generate signature info for verification"

            outputs.file(outSrc)
            doLast {
                addManagerApp(variantLowered)

                val sign = android.buildTypes[variantLowered].signingConfig
                outSrc.asFile.parentFile.mkdirs()
                val certificateInfo = KeystoreHelper.getCertificateInfo(
                    sign?.storeType,
                    sign?.storeFile,
                    sign?.storePassword,
                    sign?.keyPassword,
                    sign?.keyAlias
                )
                PrintStream(outSrc.asFile).apply {
                    println("package org.apkesu.app.zygote;")
                    println("public final class Magic {")
                    print("public static final byte[] magicNumbers = {")
                    val bytes = certificateInfo.certificate.encoded
                    print(bytes.joinToString(",") { it.toString() })
                    println("};")
                    println("}")
                }
            }
        }
        variant.registerJavaGeneratingTask(signInfoTask, outSrcDir.get().asFile)

        val kotlinCompileTask = tasks.findByName("compile${variantCapped}Kotlin") as KotlinCompile
        kotlinCompileTask.dependsOn(signInfoTask)
        val srcSet = objects.sourceDirectorySet("magic", "magic").srcDir(outSrcDir)
        kotlinCompileTask.source(srcSet)
    }
}

fun addManagerApp(variant: String) {
    val builtFile = File(
        layout.buildDirectory.get().asFile.toString().replace(project.name, "app"),
        "outputs/apk/$variant/${rootProject.name}-${android.defaultConfig.versionName}-${variant}.apk",
    )

    if (!builtFile.exists()) {
        throw GradleException("The manager app for $variant ($builtFile) is not built yet")
    }

    builtFile.copyTo(
        File(android.sourceSets[variant].assets.srcDirs.first(), "manager.apk"),
        overwrite = true,
    )
}

zygisk {
    // inject to system_server
    packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER)

    // module properties
    id = "apkesu_zygisk"
    name = "ApkeSU Zygisk"
    author = "Mocheng778"
    description = "A Zygisk backend for ApkeSU"
    entrypoint = "org.apkesu.app.zygote.ZygoteEntry"
    archiveName = "${rootProject.name}-ZYGISK-${android.defaultConfig.versionName}"
    updateJson = "https://raw.githubusercontent.com/Mocheng778/Apke_OSS/apkesu-v1.0.0/update.json"
    isAddVariantToArchiveName = true
}

dependencies {
    implementation(projects.common)
    compileOnly(projects.stub)

    implementation(libs.androidx.annotation.jvm)
    implementation(libs.io.github.vova7878.r8annotations)
    implementation(libs.dev.rikka.hidden.compat)

    api(androidvmtools.panama.core)
    api(androidvmtools.panama.unsafe)
    api(androidvmtools.panama.llvm)

    implementation(androidvmtools.sun.cleaner)
}
