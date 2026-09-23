# syntax=docker/dockerfile:1

# --- Build: собираем статику Vite ---
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# В production-сборке моки (MSW) не запускаются: фронт ходит в реальный backend через nginx-proxy.
RUN npm run build

# --- Runtime: nginx отдаёт статику и проксирует /api на backend ---
FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
