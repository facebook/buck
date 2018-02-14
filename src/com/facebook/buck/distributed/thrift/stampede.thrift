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

# Uniquely identifies a stampede distributed build
struct StampedeId {
  1 : optional string id;
}

# Uniquely identifies the run of a specific BuildSlave server.
# One StampedeId will have one or more BuildSlaveRunId's associated with it.
# (one BuildSlaveRunId per Minion that contributes to the build).
struct BuildSlaveRunId {
  1 : optional string id;
}

# Each log (stdout/stderr) is split into batches before being stored.
# This is to allow for paging, and to prevent us going over the capacity
# for an individual shard.
struct LogLineBatch {
  1: optional i32 batchNumber;
  2: optional list<string> lines;
  # This is used as an optimization to prevent having to count every
  # line each time an update happens.
  3: optional i32 totalLengthBytes;
}

struct FileInfo {
  1: optional string contentHash;
  2: optional binary content;
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

struct BuildSlaveInfo {
  1: optional BuildSlaveRunId buildSlaveRunId;
  2: optional string hostname;

  // TODO(ruibm): Fields [4-7] have fallen out of sync and should not be used anymore however
  //              the buck client code otherwise we get compile errors.
  4: optional i32 stdOutCurrentBatchNumber;
  5: optional i32 stdOutCurrentBatchLineCount;
  6: optional i32 stdErrCurrentBatchNumber;
  7: optional i32 stdErrCurrentBatchLineCount;

  8: optional bool logDirZipWritten;
  10: optional BuildStatus status = BuildStatus.UNKNOWN;
}

enum LogStreamType {
  UNKNOWN = 0,
  STDOUT = 1,
  STDERR = 2,
}

# Unique identifier for a stream at a slave.
struct SlaveStream {
  1: optional BuildSlaveRunId buildSlaveRunId;
  2: optional LogStreamType streamType;
}

struct LogDir {
    1: optional BuildSlaveRunId buildSlaveRunId;
    2: optional binary data;
    3: optional string errorMessage;
}

struct StreamLogs {
    1: optional SlaveStream slaveStream;
    2: optional list<LogLineBatch> logLineBatches;
    3: optional string errorMessage;
}

struct ScribeData {
  1: optional string category;
  2: optional list<string> lines;
}

enum LogRequestType {
  UNKNOWN = 0,
  SCRIBE_DATA = 1,
}

enum BuildMode {
  UNKNOWN = 0,
  REMOTE_BUILD = 1,
  // A random BuildSlave will be the Coordinator.
  DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR = 2
  // The machine launching the build is the Coordinator.
  DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR = 3,
}

struct PathInfo {
  1: optional string contentHash;
  2: optional string path;
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

struct BuildModeInfo {
  1: optional BuildMode mode = BuildMode.UNKNOWN;
  2: optional i32 numberOfMinions;
  3: optional string coordinatorAddress;
  4: optional i32 coordinatorPort;
}

struct BuildJob {
  1: optional StampedeId stampedeId;
  3: optional BuildStatus status = BuildStatus.UNKNOWN;
  4: optional BuckVersion buckVersion;
  6: optional list<PathInfo> dotFiles;
  7: optional BuildModeInfo buildModeInfo;
  8: optional string repository;
  9: optional string tenantId;
  10: optional string statusMessage;
  // The build UUID of a buck client which initiated
  // remote or distributed build.
  11: optional string buckBuildUuid;
  // The user that created the build.
  12: optional string username;
  13: optional list<BuildSlaveInfo> buildSlaves;
}

struct Announcement {
  1: optional string errorMessage;
  2: optional string solutionMessage;
}

##############################################################################
## Build slave structs
##############################################################################

# See build_slave.thrift in Buck client for individual event thrift structs.
struct SequencedBuildSlaveEvent {
  1: optional i32 eventNumber;
  2: optional binary event;
}

# Queries for all events with event number great than or equal to
# firstEventNumber, for build that took place at the slave identified
# by stampedeId/buildSlaveRunId.
struct BuildSlaveEventsQuery {
  1: optional StampedeId stampedeId;
  2: optional BuildSlaveRunId buildSlaveRunId;
  3: optional i32 firstEventNumber;
}

# The result of a BuildSlaveEventsQuery (contained as 'query' for reference).
# If success == true, events contains the result of the query, otherwise
# errorMessage contains an error string.
struct BuildSlaveEventsRange {
  1: optional bool success;
  2: optional string errorMessage;
  3: optional BuildSlaveEventsQuery query;
  4: optional list<SequencedBuildSlaveEvent> events;
}

##############################################################################
## Request/Response structs
##############################################################################

# Creates a brand new distributed build request with some initial configuration.
# NOTE: The distributed build won't start at this point.
struct CreateBuildRequest {
  1: optional i64 createTimestampMillis;
  2: optional BuildMode buildMode = BuildMode.REMOTE_BUILD;
  // Maximum number of minions to be used in this distributed build.
  3: optional i32 numberOfMinions;
  4: optional string repository;
  5: optional string tenantId;
  6: optional string buckBuildUuid;
  7: optional string username;
  8: optional list<string> buildTargets;
  9: optional string buildLabel;
}

struct CreateBuildResponse {
  1: optional BuildJob buildJob;
  2: optional bool wasAccepted;
  3: optional string rejectionMessage;
}

# Request for the servers to start a distributed build.
struct StartBuildRequest {
  1: optional StampedeId stampedeId;
  2: optional bool enqueueJob = true;
}

struct StartBuildResponse {
  1: optional BuildJob buildJob;
}

struct BuildStatusRequest {
  1: optional StampedeId stampedeId;
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
  1: optional StampedeId stampedeId;
  2: optional binary buildGraph;
}

struct FetchBuildGraphRequest {
  1: optional StampedeId stampedeId;
}

struct FetchBuildGraphResponse {
  1: optional binary buildGraph;
}

# Used to specify the BuckVersion a distributed build will use.
struct SetBuckVersionRequest {
  1: optional StampedeId stampedeId;
  2: optional BuckVersion buckVersion;
}

# Used to store the paths and hashes of dot-files associated with a distributed
# build.
struct SetBuckDotFilePathsRequest {
  1: optional StampedeId stampedeId;
  2: optional list<PathInfo> dotFiles;
}

struct MultiGetBuildSlaveLogDirRequest {
  1: optional StampedeId stampedeId;
  2: optional list<BuildSlaveRunId> buildSlaveRunIds;
}

# Returns zipped up log directories in the same order as the buildSlaveRunIds
# that were specified in MultiGetBuildSlaveLogDirRequest. If a particular
# buildSlaveRunId is missing, then an 'error' is set in the individual LogDir
# entry and no 'data' will be present in the same entry.
struct MultiGetBuildSlaveLogDirResponse {
  1: optional list<LogDir> logDirs;
}

# Uniquely identifies a log stream at a particular build slave,
# and the first batch number to request. Batches numbers start at 1.
struct LogLineBatchRequest {
  1: optional SlaveStream slaveStream;
  2: optional i32 batchNumber;
}

struct MultiGetBuildSlaveRealTimeLogsRequest {
  1: optional StampedeId stampedeId;
  2: optional list<LogLineBatchRequest> batches;
}

# Returns all LogLineBatches >= those specified in
# MultiGetBuildSlaveRealTimeLogsRequest. If no LogLineBatches exist for a given
# LogLineBatchRequest then an error will be returned.
struct MultiGetBuildSlaveRealTimeLogsResponse {
  1: optional list<StreamLogs> multiStreamLogs;
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

struct UpdateBuildSlaveStatusRequest {
  1: optional StampedeId stampedeId;
  2: optional BuildSlaveRunId buildSlaveRunId;
  3: optional binary buildSlaveStatus;
}

struct UpdateBuildSlaveStatusResponse {
}

# Retrieves binary encoded build slave status for the given buildSlaveRunId.
# Structure of build status can be found in client-side build_slave.thrift.
struct FetchBuildSlaveStatusRequest {
  1: optional StampedeId stampedeId;
  2: optional BuildSlaveRunId buildSlaveRunId;
}

struct FetchBuildSlaveStatusResponse {
  # If the status existed, it will be set here. Otherwise field left unset
  1: optional binary buildSlaveStatus;
}

struct StoreBuildSlaveFinishedStatsRequest {
  1: optional StampedeId stampedeId;
  2: optional BuildSlaveRunId buildSlaveRunId;
  3: optional binary buildSlaveFinishedStats;
}

struct StoreBuildSlaveFinishedStatsResponse {
}

# Retrieves binary encoded build slave stats for the given buildSlaveRunId.
# Structure of the stats object can be found in client-side build_slave.thrift.
struct FetchBuildSlaveFinishedStatsRequest {
  1: optional StampedeId stampedeId;
  2: optional BuildSlaveRunId buildSlaveRunId;
}

struct FetchBuildSlaveFinishedStatsResponse {
  # If the stats object existed, it will be set here. Otherwise field left unset
  1: optional binary buildSlaveFinishedStats;
}

# Used by build slaves to stream events (e.g. console events) back to the
# client that initiated the distributed build.
struct AppendBuildSlaveEventsRequest {
  1: optional StampedeId stampedeId;
  2: optional BuildSlaveRunId buildSlaveRunId;
  3: optional list<binary> events;
}

struct AppendBuildSlaveEventsResponse {
}

# Requests the frontend perform the given BuildSlaveEventsQuery queries.
# Results are returned inside a MultiGetBuildSlaveEventsResponse.
struct MultiGetBuildSlaveEventsRequest {
  1: optional list<BuildSlaveEventsQuery> requests;
}

struct MultiGetBuildSlaveEventsResponse {
  1: optional list<BuildSlaveEventsRange> responses;
}

# Contains details about when a cache artifact was stored/fetched to a
# particular backing store.
struct RuleKeyStoreLogEntry {
  1: optional string storeId;
  2: optional i64 storeTTLSeconds;
  3: optional i64 lastStoreEpochSeconds;
  4: optional i64 lastAttemptedStoreEpochSeconds;
  5: optional i64 lastFetchEpochSeconds;
}

struct RuleKeyLogEntry {
  1: optional string ruleKey;

  2: optional bool wasStored; // Deprecated
  3: optional i64 lastStoredTimestampMillis; // Deprecated

  4: optional list<RuleKeyStoreLogEntry> storeLogEntries;
}

struct FetchRuleKeyLogsRequest {
  1: optional list<string> ruleKeys;

  2: optional string repository;
  3: optional string scheduleType;
  4: optional bool distributedBuildModeEnabled;
}

struct FetchRuleKeyLogsResponse {
  1: optional list<RuleKeyLogEntry> ruleKeyLogs;
}

struct SetCoordinatorRequest {
  1: optional StampedeId stampedeId;
  2: optional string coordinatorHostname;
  3: optional i32 coordinatorPort;
}

struct SetCoordinatorResponse {
}

struct EnqueueMinionsRequest {
  1: optional StampedeId stampedeId;
  2: optional string minionQueue;
  3: optional i32 numberOfMinions;
}

struct EnqueueMinionsResponse {
}

struct SetFinalBuildStatusRequest {
  1: optional StampedeId stampedeId;
  2: optional BuildStatus buildStatus;
  3: optional string buildStatusMessage;
}

struct SetFinalBuildStatusResponse {
}

struct ReportCoordinatorAliveRequest {
  1: optional StampedeId stampedeId;
}

struct ReportCoordinatorAliveResponse {
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
  SET_DOTFILE_PATHS = 14,
  GET_BUILD_SLAVE_LOG_DIR = 15,
  GET_BUILD_SLAVE_REAL_TIME_LOGS = 16,
  UPDATE_BUILD_SLAVE_STATUS = 17,
  FETCH_BUILD_SLAVE_STATUS = 18,
  APPEND_BUILD_SLAVE_EVENTS = 19,
  MULTI_GET_BUILD_SLAVE_EVENTS = 20,
  SET_BUILD_MODE = 21,
  FETCH_RULE_KEY_LOGS = 22,
  STORE_BUILD_SLAVE_FINISHED_STATS = 23,
  FETCH_BUILD_SLAVE_FINISHED_STATS = 24,
  SET_COORDINATOR = 25,
  ENQUEUE_MINIONS = 26,
  SET_FINAL_BUILD_STATUS = 27,
  REPORT_COORDINATOR_ALIVE = 28,

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
  15: optional SetBuckDotFilePathsRequest setBuckDotFilePathsRequest;
  16: optional MultiGetBuildSlaveLogDirRequest multiGetBuildSlaveLogDirRequest;
  17: optional MultiGetBuildSlaveRealTimeLogsRequest
    multiGetBuildSlaveRealTimeLogsRequest;
  18: optional UpdateBuildSlaveStatusRequest updateBuildSlaveStatusRequest;
  19: optional FetchBuildSlaveStatusRequest fetchBuildSlaveStatusRequest;
  20: optional AppendBuildSlaveEventsRequest appendBuildSlaveEventsRequest;
  21: optional MultiGetBuildSlaveEventsRequest multiGetBuildSlaveEventsRequest;
  22: optional FetchRuleKeyLogsRequest fetchRuleKeyLogsRequest;
  23: optional StoreBuildSlaveFinishedStatsRequest
    storeBuildSlaveFinishedStatsRequest;
  24: optional FetchBuildSlaveFinishedStatsRequest
    fetchBuildSlaveFinishedStatsRequest;
  25: optional SetCoordinatorRequest setCoordinatorRequest;
  26: optional EnqueueMinionsRequest enqueueMinionsRequest;
  27: optional SetFinalBuildStatusRequest setFinalBuildStatusRequest;
  28: optional ReportCoordinatorAliveRequest reportCoordinatorAliveRequest;

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
  20: optional MultiGetBuildSlaveLogDirResponse
    multiGetBuildSlaveLogDirResponse;
  21: optional MultiGetBuildSlaveRealTimeLogsResponse
    multiGetBuildSlaveRealTimeLogsResponse;
  22: optional UpdateBuildSlaveStatusResponse updateBuildSlaveStatusResponse;
  23: optional FetchBuildSlaveStatusResponse fetchBuildSlaveStatusResponse;
  24: optional AppendBuildSlaveEventsResponse appendBuildSlaveEventsResponse;
  25: optional MultiGetBuildSlaveEventsResponse
    multiGetBuildSlaveEventsResponse;
  26: optional FetchRuleKeyLogsResponse fetchRuleKeyLogsResponse;
  27: optional StoreBuildSlaveFinishedStatsResponse
    storeBuildSlaveFinishedStatsResponse;
  28: optional FetchBuildSlaveFinishedStatsResponse
    fetchBuildSlaveFinishedStatsResponse;
  29: optional SetCoordinatorResponse setCoordinatorResponse;
  30: optional EnqueueMinionsResponse enqueueMinionsResponse;
  31: optional SetFinalBuildStatusResponse setFinalBuildStatusResponse;
  32: optional ReportCoordinatorAliveResponse reportCoordinatorAliveResponse;

  // [100-199] Values are reserved for the buck cache request types.
}
