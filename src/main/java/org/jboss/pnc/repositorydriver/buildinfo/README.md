# BuildInfo Converter

## Overview

The `BuildInfoConverter` utility converts PNC's `TrackingReport` into JFrog's standard BuildInfo format (`org.jfrog.build.api.Build`).

BuildInfo is the industry-standard format used by JFrog Artifactory to track build metadata, including:
- Artifacts produced during a build (uploads)
- Dependencies consumed during a build (downloads)
- Build metadata (name, number, timestamps, agent information)

## Purpose

This converter enables PNC to:
1. Export build information in a format compatible with JFrog Artifactory
2. Upload complete build metadata to Artifactory in a single operation
3. Leverage Artifactory's build promotion capabilities
4. Provide standardized build metadata for artifact traceability

## Key Design

**Three-Build Architecture for Independent Promotion**

The converter creates **three separate Build objects** to enable independent promotion. Artifactory promotes ALL artifacts or ALL dependencies across ALL modules in a Build simultaneously when `promoteBuild()` is called — it cannot filter by individual module. Using separate Build objects gives full control over what is promoted where.

| Build | Method | Build name | Module ID | Payload | Promotion target |
|---|---|---|---|---|---|
| **Primary** | `fromTrackingReport()` | `{buildName}` | `{buildName}:{buildNumber}` | Uploads as artifacts + all filtered Maven/NPM downloads as dependencies (audit only) | `{project}-{buildPromotionTarget}` — artifacts only |
| **Dependencies** | `createDependenciesBuild()` | `{buildName}:dependencies` | `{buildName}:dependencies:{buildNumber}` | Promotable Maven/NPM downloads as dependencies | `{project}-mvn-imports` / `{project}-npm-imports` |
| **Generic** | `createGenericDownloadsBuild()` | `{buildName}:generic` | `{buildName}:generic:{buildNumber}` | Generic downloads as **artifacts** (enables `build.name`/`build.number` property attachment for move-promotion) | `{project}-gen-downloads` |

> **Why store generic downloads as artifacts (not dependencies)?**
> Artifactory only sets `build.name` and `build.number` properties on items that are registered as _artifacts_ in a BuildInfo. Generic downloads must be stored as artifacts so Artifactory can attach these properties — without them the move-based promotion used for generic repos cannot identify the correct items.

The `BuildInfoPromotion` record wraps all three Build objects together with their respective `RepositoryId` targets.

## Usage

### Creating the Primary Build

```java
// Get tracking report from PNC
TrackingReport report = trackingServiceClient.getReport(buildContentId);

// Convert TrackingReport to primary Build object.
// The trackingID from the report is used as the build number.
Build primaryBuild = BuildInfoConverter.fromTrackingReport(
    report,
    "pnc",                    // projectName
    "my-build-name",          // buildName
    RepositoryType.MAVEN,     // repositoryType
    "Maven",                  // buildAgentName
    "3.8.1",                  // buildAgentVersion
    Build.formatBuildStarted(System.currentTimeMillis())  // startTime
);

// Upload to Artifactory
artifactory.builds().uploadBuild(primaryBuild);
```

The primary Build receives:
- All **uploads** as `Artifact` objects.
- All filtered **Maven/NPM downloads** as `Dependency` objects (audit trail, not individually promoted).

### Creating the Dependencies Build

```java
// Collect promotable Maven/NPM downloads
Set<TrackedEntry> promotableDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() != PackageType.GENERIC)
    .collect(Collectors.toSet());

Build dependenciesBuild = BuildInfoConverter.createDependenciesBuild(
    promotableDownloads,
    "pnc",
    "my-build-name",
    report.getTrackingID(),
    "Maven",
    "3.8.1",
    Build.formatBuildStarted(System.currentTimeMillis())
);

if (dependenciesBuild != null) {
    artifactory.builds().uploadBuild(dependenciesBuild);
}
```

### Creating the Generic Downloads Build

```java
// Collect generic downloads
Set<TrackedEntry> genericDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() == PackageType.GENERIC)
    .collect(Collectors.toSet());

Build genericBuild = BuildInfoConverter.createGenericDownloadsBuild(
    genericDownloads,
    "pnc",
    "my-build-name",
    report.getTrackingID(),
    "Maven",
    "3.8.1",
    Build.formatBuildStarted(System.currentTimeMillis())
);

if (genericBuild != null) {
    artifactory.builds().uploadBuild(genericBuild);
}
```

**Note**: All three methods return `null` if the relevant artifact/download set is empty or null (except `fromTrackingReport()` which always returns a Build — it may contain an empty artifacts or dependencies list).

### Promoting Builds

After uploading, promote each Build independently:

```java
// 1. Promote primary Build artifacts
BuildPromotionRequest artifactsPromotion = new BuildPromotionRequest();
artifactsPromotion.setTargetRepo("pnc-mvn-ibm-builds");
artifactsPromotion.setStatus("promoted");
artifactsPromotion.setComment("Promoted by PNC Repository Driver - artifacts");
artifactsPromotion.setCopy(true);
artifactsPromotion.setArtifacts(true);
artifactsPromotion.setDependencies(false);

artifactory.builds().promoteBuild(
    primaryBuild.getName(),
    primaryBuild.getNumber(),
    artifactsPromotion
);

// 2. Promote dependencies Build (Maven/NPM downloads) to shared imports
BuildPromotionRequest dependenciesPromotion = new BuildPromotionRequest();
dependenciesPromotion.setTargetRepo("pnc-mvn-imports");
dependenciesPromotion.setStatus("promoted");
dependenciesPromotion.setComment("Promoted by PNC Repository Driver - dependencies");
dependenciesPromotion.setCopy(true);
dependenciesPromotion.setArtifacts(false);
dependenciesPromotion.setDependencies(true);

if (dependenciesBuild != null) {
    artifactory.builds().promoteBuild(
        dependenciesBuild.getName(),
        dependenciesBuild.getNumber(),
        dependenciesPromotion
    );
}

// 3. Promote generic downloads Build using move (artifacts=true, dependencies=false)
if (genericBuild != null) {
    BuildPromotionRequest genericPromotion = new BuildPromotionRequest();
    genericPromotion.setTargetRepo("pnc-gen-downloads");
    genericPromotion.setStatus("promoted");
    genericPromotion.setComment("Promoted by PNC Repository Driver - generic downloads");
    genericPromotion.setCopy(false);  // move, not copy
    genericPromotion.setArtifacts(true);   // generic downloads stored as artifacts
    genericPromotion.setDependencies(false);

    artifactory.builds().promoteBuild(
        genericBuild.getName(),
        genericBuild.getNumber(),
        genericPromotion
    );
}
```

### Serializing to JSON

```java
ObjectMapper mapper = new ObjectMapper();
String buildInfoJson = mapper.writeValueAsString(build);

// Or write to file
mapper.writeValue(new File("build-info.json"), build);
```

## Build Object Structure

### Primary Build

**Core Properties:**
- `version`: BuildInfo schema version (`1.0.1`)
- `name`: Build name
- `number`: Build number (= `trackingID` from the `TrackingReport`)
- `project`: Project name (e.g., `pnc`)
- `started`: ISO 8601 formatted start timestamp
- `buildAgent`: Information about the build tool (e.g., "Maven", "Gradle")
- `agent`: Information about the CI server (`PNC-Repository-Driver`)

**Module** (`id` = `{buildName}:{buildNumber}`, `type` from `RepositoryType`):
- `artifacts`: Produced artifacts converted from `TrackingReport` uploads
- `dependencies`: Consumed Maven/NPM downloads (audit trail, not promoted from this Build)

### Dependencies Build

**Module** (`id` = `{buildName}:dependencies:{buildNumber}`, `type` = `"dependencies"`):
- `dependencies`: Promotable Maven/NPM downloads — promoted to the shared imports repository

### Generic Downloads Build

**Module** (`id` = `{buildName}:generic:{buildNumber}`, `type` = `"generic"`):
- `artifacts`: Generic downloads — stored as **artifacts** (not dependencies) so Artifactory can attach `build.name`/`build.number` properties required for move-promotion

### Artifact / Dependency Fields

**Artifact fields (uploads and generic downloads):**
- `type`: Artifact type string (see [Artifact Type Determination](#artifact-type-determination))
- `name`: File name
- `sha256`, `sha1`, `md5`: Checksums
- `remotePath`: Artifact path within the repository (serialised as `"path"` in JSON)
- `originalDeploymentRepo`: Source repository key

**Dependency fields (Maven/NPM downloads):**
- `type`: Artifact type string
- `id`: `{repoKey}/{path}` — disambiguates the same path from different repos
- `sha256`, `sha1`, `md5`: Checksums
- `remotePath`: Path within the repository (serialised as `"path"` in JSON)

## Example Output

### Primary Build JSON

```json
{
  "version": "1.0.1",
  "name": "my-build",
  "number": "build-123",
  "project": "pnc",
  "started": "2024-01-15T10:30:00.000+0000",
  "buildAgent": {
    "name": "Maven",
    "version": "3.8.1"
  },
  "agent": {
    "name": "PNC-Repository-Driver",
    "version": "4.0.0"
  },
  "modules": [
    {
      "id": "my-build:build-123",
      "type": "maven",
      "artifacts": [
        {
          "type": "jar",
          "name": "myapp-1.0.0.jar",
          "sha256": "abc123...",
          "sha1": "def456...",
          "md5": "ghi789...",
          "path": "org/example/myapp/1.0.0/myapp-1.0.0.jar",
          "originalDeploymentRepo": "pnc-mvn-build-repo"
        }
      ],
      "dependencies": [
        {
          "type": "jar",
          "id": "pnc-mvn-imports/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
          "sha256": "dep123...",
          "sha1": "dep456...",
          "md5": "dep789...",
          "path": "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar"
        }
      ]
    }
  ]
}
```

### Dependencies Build JSON

```json
{
  "version": "1.0.1",
  "name": "my-build:dependencies",
  "number": "build-123",
  "project": "pnc",
  "modules": [
    {
      "id": "my-build:dependencies:build-123",
      "type": "dependencies",
      "dependencies": [
        {
          "type": "jar",
          "id": "pnc-mvn-imports/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
          "sha256": "dep123...",
          "path": "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar"
        }
      ]
    }
  ]
}
```

### Generic Downloads Build JSON

```json
{
  "version": "1.0.1",
  "name": "my-build:generic",
  "number": "build-123",
  "project": "pnc",
  "modules": [
    {
      "id": "my-build:generic:build-123",
      "type": "generic",
      "artifacts": [
        {
          "type": "zip",
          "name": "file.zip",
          "sha256": "gen123...",
          "path": "example.com/path/to/file.zip",
          "originalDeploymentRepo": "pnc-gen-temp-build-repo"
        }
      ]
    }
  ]
}
```

## Artifact Type Determination

The converter uses JFrog's canonical `getTypeString()` algorithm to determine artifact types:

### Maven Artifacts
Uses `ArtifactPathInfo` to extract Maven GAVTC (Group, Artifact, Version, Type, Classifier), then applies JFrog's algorithm:
- **JAR with classifier**: Uses classifier as type (e.g., `"sources"`, `"javadoc"`, `"tests"`)
- **JAR without classifier**: Type is `"jar"`
- **POM**: Type is `"pom"`
- **Other types**: Appends extension with hyphen if not already present (e.g., `"war"`, `"bundle-jar"`)

### NPM and Generic Artifacts
Uses the file extension as the type (e.g., `"tgz"`, `"zip"`, `"tar.gz"`).

### Special Cases
- **Maven artifacts without extensions**: Uses `.empty` substitute extension (NCL-7238 workaround — see `MAVEN_SUBSTITUTE_EXTENSION` constant in `BuildInfoConverter`)
- **Type length limit**: Falls back to the original type if the result exceeds 64 characters (Artifactory limit)

## Integration with Artifactory — Complete Workflow

```java
// 1. Build completes; obtain tracking report
TrackingReport report = trackingServiceClient.getReport(buildContentId);
String trackingId = report.getTrackingID();

// 2. Partition downloads by package type
Set<TrackedEntry> promotableDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() != PackageType.GENERIC)
    .collect(Collectors.toSet());

Set<TrackedEntry> genericDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() == PackageType.GENERIC)
    .collect(Collectors.toSet());

String startTime = Build.formatBuildStarted(System.currentTimeMillis());

// 3. Build and upload Primary Build (all uploads + all non-generic downloads)
Build primaryBuild = BuildInfoConverter.fromTrackingReport(
    report, "pnc", "my-project", RepositoryType.MAVEN, "Maven", "3.8.1", startTime);
artifactory.builds().uploadBuild(primaryBuild);

// 4. Promote primary artifacts
BuildPromotionRequest artifactsPromotion = new BuildPromotionRequest();
artifactsPromotion.setTargetRepo("pnc-mvn-ibm-builds");
artifactsPromotion.setArtifacts(true);
artifactsPromotion.setDependencies(false);
artifactsPromotion.setCopy(true);
artifactory.builds().promoteBuild(primaryBuild.getName(), primaryBuild.getNumber(), artifactsPromotion);

// 5. Build and upload Dependencies Build (promotable Maven/NPM downloads)
Build dependenciesBuild = BuildInfoConverter.createDependenciesBuild(
    promotableDownloads, "pnc", "my-project", trackingId, "Maven", "3.8.1", startTime);
if (dependenciesBuild != null) {
    artifactory.builds().uploadBuild(dependenciesBuild);

    BuildPromotionRequest depsPromotion = new BuildPromotionRequest();
    depsPromotion.setTargetRepo("pnc-mvn-imports");
    depsPromotion.setArtifacts(false);
    depsPromotion.setDependencies(true);
    depsPromotion.setCopy(true);
    artifactory.builds().promoteBuild(dependenciesBuild.getName(), dependenciesBuild.getNumber(), depsPromotion);
}

// 6. Build and upload Generic Downloads Build
Build genericBuild = BuildInfoConverter.createGenericDownloadsBuild(
    genericDownloads, "pnc", "my-project", trackingId, "Maven", "3.8.1", startTime);
if (genericBuild != null) {
    artifactory.builds().uploadBuild(genericBuild);

    BuildPromotionRequest genericPromotion = new BuildPromotionRequest();
    genericPromotion.setTargetRepo("pnc-gen-downloads");
    genericPromotion.setArtifacts(true);    // stored as artifacts
    genericPromotion.setDependencies(false);
    genericPromotion.setCopy(false);         // move
    artifactory.builds().promoteBuild(genericBuild.getName(), genericBuild.getNumber(), genericPromotion);
}
```

## References

- [JFrog Build API](https://github.com/jfrog/build-info/blob/master/build-info-api/src/main/java/org/jfrog/build/api/Build.java)
- [BuildInfo Schema](https://github.com/jfrog/build-info-go/blob/main/buildinfo-schema.json)
- [BuildInfo.org](https://www.buildinfo.org/)
- [JFrog BuildInfo Documentation](https://www.jfrog.com/confluence/display/JFROG/Build+Integration)
- [Artifactory Builds API](https://github.com/project-ncl/artifactory-client-java/blob/master/api/src/main/java/org/jfrog/artifactory/client/Builds.java)
- [Build Promotion Request](https://github.com/project-ncl/artifactory-client-java/blob/master/api/src/main/java/org/jfrog/artifactory/client/model/BuildPromotionRequest.java)

## Dependencies

This utility requires:
- `org.jfrog.buildinfo:build-info-api` (for `Build`, `Module`, `Artifact`, `Dependency`, `BuildInfoBuilder`)
- `org.jfrog.buildinfo:build-info-extractor` (for `BuildInfoExtractorUtils.getTypeString()`)
- `org.jboss.pnc:pnc-api` (for `TrackingReport`, `TrackedEntry`, `PackageType`)
- `org.commonjava.atlas:atlas-identities` (for `ArtifactPathInfo` — Maven GAVTC parsing)

## Testing

See `BuildInfoConverterTest` for comprehensive unit tests covering:
- `TrackingReport` with both uploads and downloads
- `TrackingReport` with only uploads or only downloads
- Empty `TrackingReport` handling
- Various package types (Maven, NPM, Generic)
- Primary, Dependencies, and Generic Build creation
- Artifact type determination using JFrog's algorithm
- NCL-7238 Maven extension workaround
