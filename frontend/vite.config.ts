import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // при VITE_USE_MOCKS=false запросы /api уходят на бэкенд (Spring Boot, порт 8080)
    proxy: { "/api": process.env.BACKEND_URL ?? "http://localhost:8080" },
  },
});
