plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false

    alias(libs.plugins.ktlint)
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // KAPT configuration for Kotlin 2.x (needed for data binding which doesn't fully support KSP)
    tasks.matching { it.javaClass.name == "org.jetbrains.kotlin.gradle.tasks.KaptTask" }
        .configureEach {
            try {
                val kaptArgsMethod = this.javaClass.methods.firstOrNull { m -> m.name == "kaptArgs" }
                if (kaptArgsMethod != null) {
                    val kaptArgs = kaptArgsMethod.invoke(this)
                    val argMethod = kaptArgs.javaClass.methods.firstOrNull { m -> m.name == "arg" && m.parameterTypes.size == 2 }
                    if (argMethod != null) {
                        val addOpens =
                            listOf(
                                "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                                "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                            )
                        addOpens.forEach { value ->
                            argMethod.invoke(kaptArgs, "--add-opens", value)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("Failed to configure kaptArgs via reflection: ${e.message}")
            }
        }
}

buildscript {
    dependencies {
        classpath(libs.oss.plugin)
    }
}

ktlint {
    android.set(true)
}

tasks.register<Copy>("installGitHook") {
    from(rootProject.file("pre-commit"))
    into(rootProject.file(".git/hooks"))
}

// Ensure the app preBuild depends on the git hook installer. Use matching/configureEach to avoid
// deprecated fileCollection/spec usage that can appear with getByPath on newer Gradle.
tasks.matching { it.path == ":app:preBuild" }.configureEach {
    dependsOn(rootProject.tasks.named("installGitHook"))
}
