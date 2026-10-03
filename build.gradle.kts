// Racine : dépendances front partagées (npm workspaces, voir package.json).
val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

tasks.register<Exec>("npmInstall") {
    group = "build"
    description = "Installe les dépendances des interfaces web (React, Vite, Tailwind)."
    inputs.files("package.json", "package-lock.json")
    outputs.file("node_modules/.package-lock.json") // témoin écrit par npm (node_modules contient des liens)
    commandLine(npm, "install", "--no-audit", "--no-fund")
}
