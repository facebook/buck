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
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/distributed/thrift/stampede.thrift

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

struct RunId {
  1 : optional string id;
}

struct BuildSlaveInfo {
  1: optional RunId runId;
  2: optional string hostname;
  3: optional string command;
  4: optional list<string> stdOut;
  5: optional list<string> stdErr;
  6: optional binary logDirZipContents;
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

  // In the initialization stage, not yet queued for building
  CREATED = 5,
}

struct ScribeData {
  1: optional string category;
  2: optional list<string> lines;
}

enum LogRequestType {
  UNKNOWN = 0,
  SCRIBE_DATA = 1,
}

struct FileInfo {
  1: optional string contentHash;
  2: optional binary content;
}

enum BuckVersionType {
  // When this is not explicitly set.
  UNKNOWN = 0,

  // Refers to a version in the buck git repository.
  GIT = 1,

  // Refers to a development version uploaded from the client.
  DEVELOPMENT = 2,
}

struct BuckVersion {
  1: optional BuckVersionType type = BuckVersionType.UNKNOWN;
  2: optional string gitHash;
  3: optional FileInfo developmentVersion;
}

struct BuildJob {
  1: optional BuildId buildId;
  2: optional DebugInfo debug;
  3: optional BuildStatus status = BuildStatus.UNKNOWN;
  4: optional BuckVersion buckVersion;
  5: optional map<string, BuildSlaveInfo> slaveInfoByRunId;
}

struct Announcement {
  1: optional string errorMessage;
  2: optional string solutionMessage;
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

# Request for the servers to start a distributed build.
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

struct CASContainsRequest {
  1: optional list<string> contentSha1s;
}

struct CASContainsResponse {
  1: optional list<bool> exists;
}

struct LogRequest {
  1: optional LogRequestType type = LogRequestType.UNKNOWN;
  2: optional ScribeData scribeData;
}

# Used to store local changed source files into stampede.
struct StoreLocalChangesRequest {
  1: optional list<FileInfo> files;
}

# This is able to fetch both source control and local changed source files.
struct FetchSourceFilesRequest {
  1: optional list<string> contentHashes;
}

struct FetchSourceFilesResponse {
  1: optional list<FileInfo> files;
}

# Used to store the buildGraph and other related information to the build.
struct StoreBuildGraphRequest {
  1: optional BuildId buildId;
  2: optional binary buildGraph;
}

struct FetchBuildGraphRequest {
  1: optional BuildId buildId;
}

struct FetchBuildGraphResponse {
  1: optional binary buildGraph;
}

# Used to specify the BuckVersion a distributed build will use.
struct SetBuckVersionRequest {
  1: optional BuildId buildId;
  2: optional BuckVersion buckVersion;
}

# Used to obtain announcements for users regarding current issues with Buck and
# solutions.
struct AnnouncementRequest {
  1: optional string buckVersion;
  2: optional string repository;
}

struct AnnouncementResponse {
  1: optional list<Announcement> announcements;
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
  SET_BUCK_VERSION = 12,
  ANNOUNCEMENT = 13,

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendRequest {
  1: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  2: optional StartBuildRequest startBuildRequest;
  3: optional BuildStatusRequest buildStatusRequest;
  6: optional LogRequest logRequest;
  7: optional CASContainsRequest casContainsRequest;
  8: optional CreateBuildRequest createBuildRequest;
  9: optional StoreLocalChangesRequest storeLocalChangesRequest;
  10: optional FetchSourceFilesRequest fetchSourceFilesRequest;
  11: optional StoreBuildGraphRequest storeBuildGraphRequest;
  12: optional FetchBuildGraphRequest fetchBuildGraphRequest;
  13: optional SetBuckVersionRequest setBuckVersionRequest;
  14: optional AnnouncementRequest announcementRequest;

  // Next Free ID: 15

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  11: optional StartBuildResponse startBuildResponse;
  12: optional BuildStatusResponse buildStatusResponse;
  15: optional CASContainsResponse casContainsResponse;
  16: optional CreateBuildResponse createBuildResponse;
  17: optional FetchSourceFilesResponse fetchSourceFilesResponse;
  18: optional FetchBuildGraphResponse fetchBuildGraphResponse;
  19: optional AnnouncementResponse announcementResponse;

  // Next Free ID: 20

  // [100-199] Values are reserved for the buck cache request types.
}
