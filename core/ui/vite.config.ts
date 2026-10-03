import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Compilé dans build/generated-web/ui : Gradle l'ajoute aux ressources du cœur (servies sous /<jeton>/ui/).
// La page est servie en /<jeton>/ et ses fichiers en /<jeton>/assets/ (route du WebServer).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: "./",
  build: { outDir: resolve(import.meta.dirname, "../build/generated-web/ui"), emptyOutDir: true },
});
