# syntax=docker/dockerfile:1

FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_LEGAL_OPERATOR=""
ARG VITE_LEGAL_CONTACT_EMAIL=""
ARG VITE_LEGAL_GRIEVANCE_OFFICER=""
ARG VITE_LEGAL_GOVERNING_LAW=""
RUN npm run build

# Runs as uid 101 and listens on 8080 (unprivileged ports only)
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
