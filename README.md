# Artifact Repository Driver

A Quarkus-based microservice that manages artifact repositories in JFrog Artifactory for the PNC (Project Newcastle) build system. It handles repository creation, artifact promotion, and build tracking.

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
- **Repository Manager**: JFrog Artifactory (via artifactory-java-client)
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

- **Artifactory**: URL and access token
- **Deployment**: Environment (prod/stage/devel)
- **Security**: OIDC/LDAP settings
- **Tracking**: Internal or external tracking service

See `application.yaml` for full configuration options.

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
Promote build artifacts (async operation).

**Request**: `RepositoryPromoteRequest`
**Note**: Result sent via callback

#### `POST /archive`
Archive downloaded artifacts.

**Request**: `ArchiveRequest`

#### `GET /{id}/repository-manager-result`
Get repository manager results for a build.

**Path**: `id` - build content ID
**Response**: `RepositoryPromoteResult`

#### `GET /version`
Get service version information.

**Response**: `ComponentVersion` with version, commit, build time


## License

Apache License 2.0 - See LICENSE.txt
