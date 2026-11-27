import { defineConfig } from "vitest/config";
import path from "node:path";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  // Pure helpers run in node; files that need a DOM opt in with `// @vitest-environment jsdom`.
  test: { include: ["src/**/*.test.{ts,tsx}"], environment: "node" },
});
