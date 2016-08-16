# Copyright 2016 Facebook. All Rights Reserved.
#
#!/usr/local/bin/thrift -cpp -py -java
#
# This .thrift file contains the protocol required by the buck client to
# communicate with the buck-frontend server.
# This protocol is under active development and
# will likely be changed in non-compatible ways
#
# Whenever you change this file please run the following command to refresh the java source code:
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/distributed/thrift/buck_dist.thrift

namespace cpp buck.common
namespace java com.facebook.buck.distributed.thrift

##############################################################################
## DataTypes
##############################################################################

struct LogRecord {
  1: optional string name;
  2: optional i64 timestampMillis;
}

struct DebugInfo {
  1: optional list<LogRecord> logBook;
}

struct BuildId {
  1 : optional string id;
}

enum BuildStatus {
  UNKNOWN = 0,

  // In the build queue waiting for an available machine.
  QUEUED = 1,

  // A build machine has started the build.
  BUILDING = 2,

  // The build has completed completely.
  FINISHED_SUCCESSFULLY = 3,

  // The build has failed.
  FAILED = 4,
}

struct ScribeData {
  1: optional string category;
  2: optional list<string> lines;
}

struct FileInfo {
  1: optional string contentHash;
  2: optional binary content;
}

##############################################################################
## Buck client build state
##############################################################################

struct BuildJob {
  1: optional BuildId buildId;
  2: optional DebugInfo debug;
  3: optional BuildStatus status = BuildStatus.UNKNOWN;
}

# Thrift doesn't universally guarantee map ordering. Using list of tuples.
struct OrderedStringMapEntry {
  1: string key;
  2: string value;
}

struct BuildJobStateBuckConfig {
  1: optional map<string, string> userEnvironment;
  2: optional map<string, list<OrderedStringMapEntry>> rawBuckConfig;
  3: optional string architecture;
  4: optional string platform;
}

struct PathWithUnixSeparators {
  1: string path;
}

struct BuildJobStateBuildTarget {
  1: optional string cellName;
  2: string baseName;
  3: string shortName;
  4: set<string> flavors;
}

struct BuildJobStateFileHashEntry {
  1: optional PathWithUnixSeparators path;
  2: optional string archiveMemberPath; // Only present if this is a path to an archive member.
  3: optional string hashCode; // The SHA1 hash of the content.
  4: optional bool isDirectory;
  // The paths to source files are relative, the paths to tools, SDKs, etc.. are absolute.
  5: optional bool pathIsAbsolute;
  6: optional binary contents;
}

struct BuildJobStateFileHashes {
  1: optional i32 cellIndex;
  2: optional list<BuildJobStateFileHashEntry> entries;
}

struct BuildJobStateTargetNode {
  1: optional i32 cellIndex;
  2: optional string rawNode;
  3: optional BuildJobStateBuildTarget buildTarget;
}

struct BuildJobStateCell {
  // This is just so we can generate a user-friendly path, we should not rely on this being unique.
  1: optional string nameHint;
  2: optional BuildJobStateBuckConfig config;
}

struct BuildJobStateTargetGraph {
  1: optional list<BuildJobStateTargetNode> nodes;
}

struct BuildJobState {
  1: optional map<i32, BuildJobStateCell> cells;
  2: optional list<BuildJobStateFileHashes> fileHashes;
  3: optional BuildJobStateTargetGraph targetGraph;
}

##############################################################################
## Request/Response structs
##############################################################################

struct CreateBuildRequest {
  1: optional i64 createTimestampMillis;
}

struct CreateBuildResponse {
  1: optional BuildJob buildJob;
}

struct StartBuildRequest {
  1: optional BuildId buildId;
}

struct StartBuildResponse {
  1: optional BuildJob buildJob;
}

struct BuildStatusRequest {
  1: optional BuildId buildId;
}

struct BuildStatusResponse {
  1: optional BuildJob buildJob;
}

enum LogRequestType {
  UNKNOWN = 0,
  SCRIBE_DATA = 1,
}

struct LogRequest {
  1: optional LogRequestType type = LogRequestType.UNKNOWN;
  2: optional ScribeData scribeData;
}

struct CASContainsRequest {
  1: optional list<string> contentSha1s;
}

struct CASContainsResponse {
  1: optional list<bool> exists;
}

struct StoreLocalChangesRequest {
  1: optional BuildId buildId;
  2: optional list<FileInfo> files;
}

struct FetchSourceFilesRequest {
  1: optional BuildId buildId;
  2: optional list<string> contentHashes;
}

struct FetchSourceFilesResponse {
  1: optional list<FileInfo> files;
}

struct StoreBuildGraphRequest {
  1: optional BuildId buildId;
  2: optional binary buildGraph;
}

struct FetchBuildGraphRequest {
  1: optional BuildId buildId;
}

struct FetchBuildGraphResponse {
  1: optional BuildId buildId;
  2: optional binary buildGraph;
}

##############################################################################
## Top-Level Buck-Frontend HTTP body thrift Request/Response format
##############################################################################

enum FrontendRequestType {
  UNKNOWN = 0,
  START_BUILD = 1,
  BUILD_STATUS = 2,
  // [3-4] Values reserved for CAS.
  LOG = 5,
  CAS_CONTAINS = 6,
  CREATE_BUILD = 7,
  STORE_LOCAL_CHANGES = 8,
  FETCH_SRC_FILES = 9,
  STORE_BUILD_GRAPH = 10,
  FETCH_BUILD_GRAPH = 11,

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendRequest {
  1: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  2: optional StartBuildRequest startBuild;
  3: optional BuildStatusRequest buildStatus;
  // [4-5] Values reserved for CAS.
  6: optional LogRequest log;
  7: optional CASContainsRequest casContainsRequest;
  8: optional CreateBuildRequest createBuildRequest;
  9: optional StoreLocalChangesRequest storeLocalChangesRequest;
  10: optional FetchSourceFilesRequest fetchSourceFilesRequest;
  11: optional StoreBuildGraphRequest storeBuildGraphRequest;
  12: optional FetchBuildGraphRequest fetchBuildGraphRequest;

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  11: optional StartBuildResponse startBuild;
  12: optional BuildStatusResponse buildStatus;
  // [13-14] Values reserved for CAS.
  15: optional CASContainsResponse casContainsResponse;
  16: optional CreateBuildResponse createBuildResponse;
  17: optional FetchSourceFilesResponse fetchSourceFilesResponse;
  18: optional FetchBuildGraphResponse fetchBuildGraphResponse;

  // [100-199] Values are reserved for the buck cache request types.
}
