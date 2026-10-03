import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Compilé dans build/generated-web/web : Gradle l'ajoute aux ressources du jar (dossier web/ du plugin).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: "./",
  build: { outDir: resolve(import.meta.dirname, "../build/generated-web/web"), emptyOutDir: true },
});
