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

**One TrackingReport → One Build Object**

A single `TrackingReport` is converted to a single `Build` object containing:
- **Artifacts** (from uploads) - what the build produced
- **Dependencies** (from downloads) - what the build consumed

This design aligns with Artifactory's `uploadBuild` API which accepts a single Build object. The Build can then be promoted using Artifactory's `promoteBuild` API, where you can specify whether to promote artifacts, dependencies, or both via the `BuildPromotionRequest`.

## Usage

### Basic Usage

```java
// Get tracking report from PNC
TrackingReport report = trackingServiceClient.getReport(buildContentId);

// Convert entire TrackingReport to a single Build object
// The trackingID from the report is automatically used as the build number
Build build = BuildInfoConverter.fromTrackingReport(
    report,
    "my-build-name"
);

// Upload to Artifactory
artifactory.builds().uploadBuild(build);
```

### With Additional Properties

```java
Map<String, String> additionalProps = new HashMap<>();
additionalProps.put("buildUrl", "https://ci.example.com/build/123");
additionalProps.put("vcsRevision", "abc123def456");
additionalProps.put("vcsUrl", "https://github.com/example/repo");

Build build = BuildInfoConverter.fromTrackingReport(
    report,
    "my-build",
    additionalProps
);
```

**Note**: The `trackingID` field from the `TrackingReport` is automatically used as the build number in the generated `Build` object. This simplifies the API and ensures consistency between PNC's tracking system and Artifactory's build info.

### Promoting a Build

After uploading the build to Artifactory, you can promote it:

```java
// Promote the build to a target repository
BuildPromotionRequest promotionRequest = new BuildPromotionRequest();
promotionRequest.setTargetRepo("releases");
promotionRequest.setStatus("Released");
promotionRequest.setComment("Promoting build to releases");

// The promotion can include artifacts, dependencies, or both
// This is controlled by the BuildPromotionRequest configuration
artifactory.builds().promoteBuild(
    build.getName(),
    build.getNumber(),
    promotionRequest
);
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

The generated `Build` object contains:

### Core Properties
- `version`: BuildInfo schema version (1.0.1)
- `name`: Build name
- `number`: Build number/version
- `started`: ISO 8601 formatted start timestamp
- `buildAgent`: Information about the build tool (PNC)
- `agent`: Information about the CI server (PNC-Repository-Driver)
- `properties`: Custom key-value properties

### Modules
Each `Build` contains one module with:
- `id`: Module identifier (buildName:buildNumber)
- `artifacts`: List of produced artifacts (from TrackingReport uploads)
- `dependencies`: List of consumed dependencies (from TrackingReport downloads)

### Artifacts (from uploads)
Each artifact includes:
- `type`: Package type (mvn, npm, generic, etc.)
- `name`: File name
- `sha256`, `sha1`, `md5`: Checksums
- `properties`: Additional metadata (path, localUrl, originUrl)

### Dependencies (from downloads)
Each dependency includes:
- `type`: Package type
- `id`: Dependency identifier (path)
- `sha256`, `sha1`, `md5`: Checksums
- `scopes`: Repository scopes

## Example Output

```json
{
  "version": "1.0.1",
  "name": "my-build",
  "number": "1.0.0",
  "started": "2024-01-15T10:30:00.000+0000",
  "buildAgent": {
    "name": "PNC",
    "version": "3.5.1"
  },
  "agent": {
    "name": "PNC-Repository-Driver",
    "version": "3.5.1"
  },
  "modules": [
    {
      "id": "my-build:1.0.0",
      "artifacts": [
        {
          "type": "mvn",
          "name": "myapp-1.0.0.jar",
          "sha256": "abc123...",
          "sha1": "def456...",
          "md5": "ghi789...",
          "properties": {
            "path": "/org/example/myapp/1.0.0/myapp-1.0.0.jar",
            "localUrl": "file:///tmp/myapp-1.0.0.jar"
          }
        }
      ],
      "dependencies": [
        {
          "type": "mvn",
          "id": "/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
          "sha256": "dep123...",
          "sha1": "dep456...",
          "md5": "dep789...",
          "scopes": ["central"]
        }
      ]
    }
  ]
}
```

## Integration with Artifactory

### Upload Build

```java
TrackingReport report = trackingServiceClient.getReport(buildContentId);
Build build = BuildInfoConverter.fromTrackingReport(report, buildName);

// Upload to Artifactory
artifactory.builds().uploadBuild(build);
```

### Promote Build

The `promoteBuild` API allows you to promote artifacts and/or dependencies to different repositories:

```java
BuildPromotionRequest request = new BuildPromotionRequest();
request.setTargetRepo("releases");
request.setStatus("Released");
request.setCopy(true); // Copy instead of move

// Promote the build using the trackingID as build number
artifactory.builds().promoteBuild(buildName, report.getTrackingID(), request);
```

The promotion behavior (whether to promote artifacts, dependencies, or both) is controlled by the `BuildPromotionRequest` configuration and Artifactory's promotion rules.

## Workflow Example

```java
// 1. Build completes, tracking report is generated
TrackingReport report = trackingServiceClient.getReport(buildContentId);
// report.getTrackingID() returns something like "build-12345"

// 2. Convert to BuildInfo format (trackingID is used as build number)
Build build = BuildInfoConverter.fromTrackingReport(
    report,
    "my-project"
);

// 3. Upload to Artifactory
artifactory.builds().uploadBuild(build);

// 4. Later, promote to releases using the trackingID as build number
BuildPromotionRequest promotionRequest = new BuildPromotionRequest();
promotionRequest.setTargetRepo("releases");
promotionRequest.setStatus("Released");
artifactory.builds().promoteBuild("my-project", report.getTrackingID(), promotionRequest);
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
- `org.jfrog.buildinfo:build-info-api:2.43.9` (or later)
- `org.jboss.pnc:pnc-api` (for TrackingReport and TrackedEntry)

## Testing

See `BuildInfoConverterTest` for comprehensive unit tests covering:
- TrackingReport with both uploads and downloads
- TrackingReport with only uploads
- TrackingReport with only downloads
- Additional properties handling
- Empty TrackingReport handling
- Various package types (Maven, NPM, Generic)