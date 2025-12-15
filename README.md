# PLM Cloud Platform Backend

Backend for the PLM microservice suite. This repo is a Maven multi-module project aggregating core services such as attribute, product, BOM, document, workflow, search, auth, and gateway.

## Tech Stack
- Java 23
- Spring Boot 3.5.x
- Maven 3.9+
- PostgreSQL 16 (Flyway for schema migration)
- Sa-Token for auth (see auth service)

## Repository Layout (modules)
- plm-common: shared domain objects, DTOs, utilities
- plm-infrastructure: shared infrastructure config (database, security, logging, etc.)
- plm-auth-service: authentication/authorization service
- plm-attribute-service: attribute & LOV management APIs
- plm-product-service, plm-bom-service, plm-document-service, plm-workflow-service, plm-search-service: domain services
- plm-gateway: API gateway/edge routing
- plm-admin: admin console backend
- plm-deployment: Docker/Helm/k8s manifests
- plm-db-migration: Flyway migrations (centralized DDL)

## Prerequisites
- JDK 23 available on PATH
- Maven 3.9+ (wrapper not provided)
- PostgreSQL reachable; for local dev you can use the provided compose file
- Git LFS not required

### Local PostgreSQL via Docker Compose
```powershell
# one-time network/volume (compose expects them as external)
docker network create pg-network
docker volume create pgdata

# start postgres
docker compose -f docker-postgre-sql/docker-compose.yaml up -d
```
Default compose creds: user admin / password p@ssw0rd@2025 / db production_db. Adjust datasource URLs or change POSTGRES_DB to match your schema (example configs below use database plm).

## Build
```powershell
# build all modules (skip tests if needed)
mvn clean install -DskipTests
```
The parent POM has a no-exec profile to avoid running Spring Boot at the aggregator level.

## Database Migration (Flyway)
Run migrations from the centralized module; supply your DB connection each time to avoid storing secrets:
```powershell
mvn -pl plm-db-migration flyway:migrate ^
  -Dflyway.url=jdbc:postgresql://localhost:5432/plm ^
  -Dflyway.user=admin ^
  -Dflyway.password=p@ssw0rd@2025 ^
  -Dflyway.schemas=plm_meta
```
See [plm-db-migration/README.md](plm-db-migration/README.md) for more commands (info/clean) and authoring rules.

## Run a Service (example: Attribute Service)
```powershell
# ensure DB is up and migrated, then run with dev profile
mvn -pl plm-attribute-service -am spring-boot:run -Dspring-boot.run.profiles=dev
```
Config overrides follow Spring conventions (e.g., SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD). Sample dev config lives in [plm-attribute-service/src/main/resources/application-dev.yml](plm-attribute-service/src/main/resources/application-dev.yml).

### Attribute API quick check
```powershell
# list attributes (paged)
curl "http://localhost:8080/api/meta/attribute-defs?page=0&size=20"

# attribute detail with LOV values
curl "http://localhost:8080/api/meta/attribute-defs/ATTR_000003?includeValues=true"
```
More details about import logic and payloads are in [plm-attribute-service/README.md](plm-attribute-service/README.md).

## Notes and Troubleshooting
- Ensure you are using JDK 23; lower versions will fail compilation.
- If compose fails to start, confirm the external network/volume exist (pg-network, pgdata).
- Align the database name between compose and your datasource URL; either set POSTGRES_DB=plm in compose or point the services to production_db.
- Flyway migration errors usually indicate version conflicts; do not edit existing V* files—add a new version.

## Related Frontend
Frontend lives in a separate repo: Altair288/plm-cloud-frontend.
