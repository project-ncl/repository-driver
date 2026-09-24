# Artifact Repository Driver

A Quarkus-based microservice that manages artifact repositories in JFrog Artifactory for the PNC (Project Newcastle) build system. It handles repository creation, artifact tracking, build-info publishing, and artifact promotion.

## Requirements

### Build Requirements
- **JDK**: 17 or higher
- **Maven**: 3.8 or higher

### Runtime Requirements
- **JFrog Artifactory**: Pro or Enterprise edition
- **Authentication**: OIDC or LDAP configured

## Technology Stack

- **Framework**: Quarkus 3.x (uber-jar packaging)
- **Language**: Java 17
- **Repository Manager**: JFrog Artifactory (via `artifactory-java-client`)
- **Build Tracking**: JFrog BuildInfo API (`build-info-api`, `build-info-extractor`)
- **Security**: OIDC/LDAP authentication with role-based access control
- **Observability**: OpenTelemetry tracing, Kafka logging

## Building

### Build and Test
```bash
mvn clean install
```
Produces: `target/repository-driver-runner.jar` (uber-jar)

## Running

### Using the uber-jar
```bash
java -jar target/repository-driver-runner.jar
```

### Development Mode (with hot reload)
```bash
mvn quarkus:dev
```

A Swagger UI is served at `/q/swagger-ui` and a summary landing page at `/`.

## Development

### Code Formatting
Code is automatically formatted on compile using Spotless:
```bash
mvn spotless:apply
```

### Running Single Test
```bash
mvn test -Dtest=TrackingReportProcessorTest
```

## Configuration

Configuration is managed through `src/main/resources/application.yaml`. Key settings:

### Artifactory Client

Three separate Artifactory client tokens are used for isolation:

| Property | Description |
|---|---|
| `repository-driver.artifactory-client.url` | Artifactory base URL |
| `repository-driver.artifactory-client.tokens.admin` | Admin token for repository/group management |
| `repository-driver.artifactory-client.tokens.package-promotion` | Token scoped to Maven/NPM promotion |
| `repository-driver.artifactory-client.tokens.generic-promotion` | Token scoped to generic artifact promotion |
| `repository-driver.artifactory-client.connect-timeout` | TCP connect timeout in seconds (default: 5) |
| `repository-driver.artifactory-client.request-timeout` | HTTP request timeout in seconds (default: 180) |

### Deployment / Project

| Property | Description |
|---|---|
| `repository-driver.artifactory-project` | Artifactory project prefix (e.g., `pnc`, `pnc-stage`, `pnc-devel`) |
| `repository-driver.self-base-url` | Public URL of this service (used in callbacks) |

### Build Categories

Build categories (e.g. `default`, `standard`) control which repositories are used for promotion.
Each category has the following configurable sub-keys under `repository-driver.build-categories.<category>`:

| Sub-key | Description |
|---|---|
| `build-promotion-target` | Target repository for permanent build artifact promotion |
| `temp-build-promotion-target` | Target repository for temporary build promotion |
| `build-group-constituents.temp-hosted` | Hosted repos added to temp virtual groups |
| `build-group-constituents.temp-group` | Virtual/remote groups added to temp virtual groups |
| `build-group-constituents.hosted` | Hosted repos added to permanent virtual groups |
| `build-group-constituents.group` | Virtual/remote groups added to permanent virtual groups |

If no configuration is found for a specific category, the `default` category values are used.

### Tracking Service

| Property | Description |
|---|---|
| `repository-driver.tracking-service.api-url` | URL of the external [pnc-tracker](https://github.com/project-ncl/pnc-tracker) service |

### Artifact Filtering

Artifacts can be excluded from promotion, archiving, or results by configuring path/repo patterns:

- `repository-driver.ignored-path-patterns.promotion.{maven,npm,generic}`
- `repository-driver.ignored-path-patterns.archive.maven`
- `repository-driver.ignored-path-patterns.result.{maven,npm,generic}`
- `repository-driver.ignored-repo-patterns.{promotion,archive}`

See `application.yaml` for full configuration options.

## Architecture Overview

### Repository Lifecycle

For each PNC build, the driver creates a dedicated **virtual repository group** in Artifactory. The group wires together:

1. A **build-specific local (hosted) repository** – the exclusive deploy target for this build.
2. Pre-configured **remote proxy repositories** that provide download access to approved upstreams.
3. Extra **per-build proxy repositories** (prefixed `{buildId}-prx-`) for any additional remotes requested at build creation time.

At the end of a build, the driver:
1. Seals the tracking report.
2. Queries the [pnc-tracker](https://github.com/project-ncl/pnc-tracker) tracking service to obtain upload/download lists.
3. Converts the tracking data to **JFrog BuildInfo** and publishes it to Artifactory.
4. Promotes artifacts and dependencies to the shared permanent repositories via Artifactory's build-promotion API.
5. Marks the local build repository read-only.
6. Deletes the temporary virtual group.

### BuildInfo / Promotion Architecture

Promotion uses **three separate Build objects** because Artifactory promotes all artifacts or all dependencies in a Build simultaneously and cannot filter by module:

| Build | Name pattern | Contains | Promotes to |
|---|---|---|---|
| **Primary** | `{buildName}` | Uploads as artifacts; all filtered Maven/NPM downloads as dependencies (audit trail only) | `{project}-{buildPromotionTarget}` (artifacts only) |
| **Dependencies** | `{buildName}:dependencies` | Promotable Maven/NPM downloads as dependencies | `{project}-mvn-imports` / `{project}-npm-imports` |
| **Generic** | `{buildName}:generic` | Generic downloads as artifacts (enables `build.name`/`build.number` property attachment) | `{project}-gen-downloads` |

The `BuildInfoPromotion` record wraps all three Build objects together with their target `RepositoryId` values.

See [`src/main/java/org/jboss/pnc/repositorydriver/buildinfo/README.md`](src/main/java/org/jboss/pnc/repositorydriver/buildinfo/README.md) for detailed BuildInfo documentation.

### Promotion Isolation

Two separate Artifactory client tokens are used for promotion to prevent cross-contamination:

- **`@PackagePromotion`** — used for Maven/NPM artifact and dependency promotion.
- **`@GenericPromotion`** — used exclusively for generic artifact promotion.

This ensures generic artifact promotions cannot inadvertently operate on Maven/NPM repositories and vice-versa.

## REST API

### Authentication
All endpoints require authentication with roles:
- `pnc-app-repository-driver-user`
- `pnc-users-admin`

### Endpoints

#### `POST /create`
Create a new repository for a build.

**Request**: `RepositoryCreateRequest`
**Response**: `RepositoryCreateResponse` with repository URLs

#### `PUT /seal`
Seal the tracking report for a build.

**Request**: `buildContentId` (String)

#### `PUT /promote`
Promote build artifacts (async operation). Uploads BuildInfo to Artifactory and triggers promotion for all three Build objects (primary, dependencies, generic).

**Request**: `RepositoryPromoteRequest`
**Note**: Result is sent via callback to the orchestrator.

#### `POST /archive`
Archive downloaded artifacts.

**Request**: `ArchiveRequest`

#### `GET /{id}/repository-manager-result`
Get repository manager results for a build.

**Path**: `id` – build content ID
**Response**: `RepositoryPromoteResult`

#### `GET /version`
Get service version information.

**Response**: `ComponentVersion` with version, commit, build time

## License

Apache License 2.0 – See LICENSE.txt
