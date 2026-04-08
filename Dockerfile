FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY . .
COPY --from=frontend-build /app/dist ./frontend/dist

ENV SKIP_NPM=true

RUN ./gradlew :backend:server:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/backend/server/build/install/server ./
COPY --from=frontend-build /app/dist ./frontend/dist

EXPOSE 8080
CMD ["./bin/server"]