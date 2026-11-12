plugins {
    //trick: for the same plugin versions in all sub-modules
    id("com.android.application").version("7.4.2").apply(false)
    id("com.android.library").version("7.4.2").apply(false)
    kotlin("android").version("2.0.21-KBA-010").apply(false)
    kotlin("multiplatform").version("2.0.21-KBA-010").apply(false)
    id("com.google.devtools.ksp").version("2.0.21-1.0.27").apply(false)

}

tasks.register("verifySharedArchitecture") {
    group = "verification"
    description = "Check shared module package layering and dependency direction"
    val checker = rootProject.file("scripts/verify-shared-architecture.py")
    inputs.file(checker)
    inputs.file("scripts/test_architecture.py")
    inputs.file("iosApp/Podfile")
    inputs.files(fileTree("iosApp/Podspecs") { include("*.podspec") })
    inputs.files(fileTree("androidApp/src") { include("**/*.kt") })
    inputs.files(fileTree("shared/src") { include("**/*.kt") })
    inputs.files(fileTree("iosApp/iosApp") { include("**/*.swift", "**/*.m", "**/*.mm", "**/*.h") })
    inputs.files(fileTree("scripts") { include("**/*.swift", "**/*.cjs", "**/*.py", "**/*.sh", "**/*.m", "**/*.h") })
    inputs.files(fileTree("ohosApp/entry/src") { include("**/*.ets", "**/*.cpp", "**/*.c", "**/*.h") })
    inputs.files(fileTree(rootDir) { include("*.kts", "shared/*.kts", "androidApp/*.kts", "ohosApp/*.ts", "ohosApp/*.sh", "ohosApp/entry/*.ts") })
    outputs.upToDateWhen { false }
    doLast {
        exec {
            commandLine("python3", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py")
        }
        exec {
            commandLine("python3", checker.absolutePath)
        }
    }
}
