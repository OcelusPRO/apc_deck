// Interface web d'un module (React + Tailwind, compilée par Vite) embarquée dans son jar.
// Le projet front est le dossier web/ (plugins) ou ui/ (cœur) du module ; Vite écrit dans build/generated-web/.
package buildsrc.convention

plugins {
    java
}

val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
val frontDir: File = listOf("web", "ui").map { file(it) }.first { it.resolve("package.json").exists() }
val generated = layout.buildDirectory.dir("generated-web")

val buildWeb = tasks.register<Exec>("buildWeb") {
    group = "build"
    description = "Compile l'interface web (tsc + Vite)."
    dependsOn(":npmInstall")
    workingDir(frontDir)
    inputs.dir(frontDir.resolve("src"))
    inputs.files(listOf("index.html", "vite.config.ts", "package.json", "tsconfig.json").map(frontDir::resolve))
    inputs.dir(rootDir.resolve("web/shared/src"))
    outputs.dir(generated)
    commandLine(npm, "run", "build")
}

sourceSets.named("main") {
    resources.srcDir(files(generated).builtBy(buildWeb))
}
