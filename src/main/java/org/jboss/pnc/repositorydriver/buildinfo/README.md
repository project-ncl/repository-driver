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

**Two-Build Architecture for Independent Promotion**

The converter creates **two separate Build objects** to enable independent promotion:

1. **Primary Build**: Contains artifacts (uploads) and non-generic dependencies (downloads)
   - Module ID: `{buildName}:{buildNumber}`
   - Artifacts: Filtered uploads
   - Dependencies: Filtered Maven/NPM downloads
   - Promoted to: artifacts target (e.g., `pnc-mvn-builds`) and dependencies target (e.g., `pnc-mvn-imports`)
   
2. **Generic Downloads Build**: Contains generic downloads as dependencies (may be null if no generic downloads)
   - Module ID: `{buildName}-generic-downloads:{buildNumber}`
   - Artifacts: None (empty list)
   - Dependencies: Generic downloads (stored as dependencies, not artifacts)
   - Promoted to: generic downloads target (e.g., `pnc-generic-downloads`)

**Why two Build objects?** Artifactory cannot differentiate between modules during promotion. When you call `promoteBuild()` with `setArtifacts(true)` or `setDependencies(true)`, Artifactory promotes ALL artifacts or ALL dependencies across ALL modules in the Build. Using separate Build objects ensures:
- Correct Artifactory semantics - each Build promoted independently
- Proper isolation - generic downloads don't interfere with primary promotion
- Semantic correctness - generic downloads stored as dependencies (consumed artifacts)
- Clear separation - distinct purposes for each Build

The `BuildInfoPromotion` record wraps both Build objects with their respective target repositories.

## Usage

### Creating Primary Build

```java
// Get tracking report from PNC
TrackingReport report = trackingServiceClient.getReport(buildContentId);

// Convert TrackingReport to primary Build object
// The trackingID from the report is automatically used as the build number
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

### Creating Generic Downloads Build

```java
// Separate generic downloads from other downloads
Set<TrackedEntry> genericDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() == PackageType.GENERIC)
    .collect(Collectors.toSet());

// Create separate Build for generic downloads (if any exist)
Build genericBuild = BuildInfoConverter.createGenericDownloadsBuild(
    genericDownloads,
    "pnc",                    // projectName
    "my-build-name",          // buildName
    report.getTrackingID(),   // buildNumber
    "Maven",                  // buildAgentName
    "3.8.1",                  // buildAgentVersion
    Build.formatBuildStarted(System.currentTimeMillis())  // startTime
);

if (genericBuild != null) {
    artifactory.builds().uploadBuild(genericBuild);
}
```

**Note**: The `trackingID` field from the `TrackingReport` is used as the build number in both Build objects. This ensures consistency between PNC's tracking system and Artifactory's build info.

### Promoting Builds

After uploading the builds to Artifactory, you can promote them independently:

```java
// Promote primary Build artifacts to build repository
BuildPromotionRequest artifactsPromotion = new BuildPromotionRequest();
artifactsPromotion.setTargetRepo("pnc-mvn-builds");
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

// Promote primary Build dependencies to shared imports
BuildPromotionRequest dependenciesPromotion = new BuildPromotionRequest();
dependenciesPromotion.setTargetRepo("pnc-mvn-imports");
dependenciesPromotion.setStatus("promoted");
dependenciesPromotion.setComment("Promoted by PNC Repository Driver - dependencies");
dependenciesPromotion.setCopy(true);
dependenciesPromotion.setArtifacts(false);
dependenciesPromotion.setDependencies(true);

artifactory.builds().promoteBuild(
    primaryBuild.getName(),
    primaryBuild.getNumber(),
    dependenciesPromotion
);

// Promote generic downloads Build (if it exists)
if (genericBuild != null) {
    BuildPromotionRequest genericPromotion = new BuildPromotionRequest();
    genericPromotion.setTargetRepo("pnc-generic-downloads");
    genericPromotion.setStatus("promoted");
    genericPromotion.setComment("Promoted by PNC Repository Driver - generic downloads");
    genericPromotion.setCopy(true);
    genericPromotion.setArtifacts(false);
    genericPromotion.setDependencies(true);  // Generic downloads stored as dependencies
    
    artifactory.builds().promoteBuild(
        genericBuild.getName(),
        genericBuild.getNumber(),
        genericPromotion
    );
}
```

### Serializing to JSON

The `Build` object can be serialized to JSON using Jackson:

```java
ObjectMapper mapper = new ObjectMapper();
String buildInfoJson = mapper.writeValueAsString(build);

// Or write to file
mapper.writeValue(new File("build-info.json"), build);
```

## Build Object Structure

### Primary Build

The primary `Build` object contains:

**Core Properties:**
- `version`: BuildInfo schema version (1.0.1)
- `name`: Build name
- `number`: Build number (trackingID)
- `project`: Project name (e.g., "pnc")
- `started`: ISO 8601 formatted start timestamp
- `buildAgent`: Information about the build tool (e.g., "Maven", "Gradle")
- `agent`: Information about the CI server (PNC-Repository-Driver)

**Module:**
- `id`: Module identifier (`buildName:buildNumber`)
- `type`: Module type based on RepositoryType (e.g., "maven", "npm")
- `artifacts`: List of produced artifacts (from TrackingReport uploads)
- `dependencies`: List of consumed dependencies (from TrackingReport non-generic downloads)

**Artifacts (from uploads):**
- `type`: Artifact type using JFrog's `getTypeString()` algorithm (e.g., "jar", "pom", "sources")
- `name`: File name
- `sha256`, `sha1`, `md5`: Checksums
- `remotePath`: Artifact path in repository
- `originalDeploymentRepo`: Source repository path

**Dependencies (from downloads):**
- `type`: Artifact type using JFrog's `getTypeString()` algorithm
- `id`: Dependency identifier (path)
- `sha256`, `sha1`, `md5`: Checksums

### Generic Downloads Build

The generic downloads `Build` object (if created) contains:

**Core Properties:** Same as primary Build

**Module:**
- `id`: Module identifier (`buildName-generic-downloads:buildNumber`)
- `type`: "generic"
- `artifacts`: Empty list
- `dependencies`: List of generic downloads (stored as dependencies, not artifacts)

**Dependencies (generic downloads):**
- `type`: File extension
- `id`: Dependency identifier (path)
- `sha256`, `sha1`, `md5`: Checksums

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
    "version": "3.5.1"
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
          "remotePath": "org/example/myapp/1.0.0/myapp-1.0.0.jar",
          "originalDeploymentRepo": "pnc-mvn-build-repo"
        }
      ],
      "dependencies": [
        {
          "type": "jar",
          "id": "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
          "sha256": "dep123...",
          "sha1": "dep456...",
          "md5": "dep789..."
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
  "name": "my-build-generic-downloads",
  "number": "build-123",
  "project": "pnc",
  "started": "2024-01-15T10:30:00.000+0000",
  "buildAgent": {
    "name": "Maven",
    "version": "3.8.1"
  },
  "agent": {
    "name": "PNC-Repository-Driver",
    "version": "3.5.1"
  },
  "modules": [
    {
      "id": "my-build-generic-downloads:build-123",
      "type": "generic",
      "artifacts": [],
      "dependencies": [
        {
          "type": "zip",
          "id": "example.com/path/to/file.zip",
          "sha256": "gen123...",
          "sha1": "gen456...",
          "md5": "gen789..."
        }
      ]
    }
  ]
}
```

## Integration with Artifactory

### Complete Workflow Example

```java
// 1. Build completes, tracking report is generated
TrackingReport report = trackingServiceClient.getReport(buildContentId);
String trackingId = report.getTrackingID(); // e.g., "build-12345"

// 2. Separate generic downloads from other downloads
Set<TrackedEntry> nonGenericDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() != PackageType.GENERIC)
    .collect(Collectors.toSet());

Set<TrackedEntry> genericDownloads = report.getDownloads().stream()
    .filter(d -> d.getRepoId().getPackageType() == PackageType.GENERIC)
    .collect(Collectors.toSet());

// 3. Create primary Build (artifacts + non-generic dependencies)
TrackingReport primaryReport = TrackingReport.builder()
    .uploads(report.getUploads())
    .downloads(nonGenericDownloads)
    .trackingID(trackingId)
    .build();

Build primaryBuild = BuildInfoConverter.fromTrackingReport(
    primaryReport,
    "pnc",
    "my-project",
    RepositoryType.MAVEN,
    "Maven",
    "3.8.1",
    Build.formatBuildStarted(System.currentTimeMillis())
);

// 4. Upload primary Build to Artifactory
artifactory.builds().uploadBuild(primaryBuild);

// 5. Promote artifacts to build repository
BuildPromotionRequest artifactsPromotion = new BuildPromotionRequest();
artifactsPromotion.setTargetRepo("pnc-mvn-builds");
artifactsPromotion.setArtifacts(true);
artifactsPromotion.setDependencies(false);
artifactory.builds().promoteBuild(
    primaryBuild.getName(),
    primaryBuild.getNumber(),
    artifactsPromotion
);

// 6. Promote dependencies to shared imports
BuildPromotionRequest dependenciesPromotion = new BuildPromotionRequest();
dependenciesPromotion.setTargetRepo("pnc-mvn-imports");
dependenciesPromotion.setArtifacts(false);
dependenciesPromotion.setDependencies(true);
artifactory.builds().promoteBuild(
    primaryBuild.getName(),
    primaryBuild.getNumber(),
    dependenciesPromotion
);

// 7. Create and promote generic downloads Build (if any)
if (!genericDownloads.isEmpty()) {
    Build genericBuild = BuildInfoConverter.createGenericDownloadsBuild(
        genericDownloads,
        "pnc",
        "my-project",
        trackingId,
        "Maven",
        "3.8.1",
        Build.formatBuildStarted(System.currentTimeMillis())
    );
    
    artifactory.builds().uploadBuild(genericBuild);
    
    BuildPromotionRequest genericPromotion = new BuildPromotionRequest();
    genericPromotion.setTargetRepo("pnc-generic-downloads");
    genericPromotion.setArtifacts(false);
    genericPromotion.setDependencies(true);
    artifactory.builds().promoteBuild(
        genericBuild.getName(),
        genericBuild.getNumber(),
        genericPromotion
    );
}
```

## Artifact Type Determination

The converter uses JFrog's canonical `getTypeString()` algorithm to determine artifact types:

### Maven Artifacts
Uses `ArtifactPathInfo` to extract Maven GAVTC (Group, Artifact, Version, Type, Classifier), then applies JFrog's algorithm:
- **JAR with classifier**: Uses classifier as type (e.g., "sources", "javadoc", "tests")
- **JAR without classifier**: Type is "jar"
- **POM**: Type is "pom"
- **Other types**: Appends extension with hyphen if not already present (e.g., "war", "bundle-jar")

### NPM and Generic Artifacts
Uses file extension as the type (e.g., "tgz", "zip", "tar.gz")

### Special Cases
- **Maven artifacts without extensions**: Uses `.empty` substitute extension (NCL-7238 workaround)
- **Type length limit**: Falls back to original type if result exceeds 64 characters (Artifactory limit)

## References

- [JFrog Build API](https://github.com/jfrog/build-info/blob/master/build-info-api/src/main/java/org/jfrog/build/api/Build.java)
- [BuildInfo Schema](https://github.com/jfrog/build-info-go/blob/main/buildinfo-schema.json)
- [BuildInfo.org](https://www.buildinfo.org/)
- [JFrog BuildInfo Documentation](https://www.jfrog.com/confluence/display/JFROG/Build+Integration)
- [Artifactory Builds API](https://github.com/project-ncl/artifactory-client-java/blob/master/api/src/main/java/org/jfrog/artifactory/client/Builds.java)
- [Build Promotion Request](https://github.com/project-ncl/artifactory-client-java/blob/master/api/src/main/java/org/jfrog/artifactory/client/model/BuildPromotionRequest.java)

## Dependencies

This utility requires:
- `org.jfrog.buildinfo:build-info-api:2.43.9` (or later)
- `org.jfrog.buildinfo:build-info-extractor:2.43.9` (for `getTypeString()` algorithm)
- `org.jboss.pnc:pnc-api` (for TrackingReport and TrackedEntry)
- `org.commonjava.atlas:atlas-identities` (for Maven artifact path parsing)

## Testing

See `BuildInfoConverterTest` for comprehensive unit tests covering:
- TrackingReport with both uploads and downloads
- TrackingReport with only uploads
- TrackingReport with only downloads
- Empty TrackingReport handling
- Various package types (Maven, NPM, Generic)
- Proper artifact type determination using JFrog's algorithm