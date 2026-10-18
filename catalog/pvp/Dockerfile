# Builds the pvp client and serves it with nginx. nginx also proxies the engine
# paths (/ws/, /api/) to ENGINE_URL, so the bundle's same-origin URLs work.
#   docker build -t pvp-web .
#   docker run -p 8080:80 -e ENGINE_URL=http://my-engine:8080 pvp-web
FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# The page origin proxies /api/ to the engine, so the avatar proxy is same-origin.
ENV VITE_AVATAR_PROXY_URL=/api/v1/avatar
RUN npm run build

FROM nginx:alpine

ENV ENGINE_URL=http://engine:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/ /usr/share/nginx/html/

EXPOSE 80
