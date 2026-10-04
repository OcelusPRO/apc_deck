// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins { // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
}

kotlin { // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(25)
    // Bytecode et API Java 17 : les modules partagés (api, core, plugins officiels) sont aussi compilés dans l'app
    // Android, dont l'outil de conversion (D8) ne lit pas les classes Java 25.
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.withType<Test>().configureEach { // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()
    
    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED
        )
    }
}
