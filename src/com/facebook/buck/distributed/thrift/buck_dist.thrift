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
}

struct BuildJobStateFileHashes {
  1: optional string fileSystemRootName;
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

enum RuleKeyStatus {
  UNKNOWN = 0,
  NEVER_STORED = 1,
  STORED_WITHIN_SLA = 2,
  OUTSIDE_SLA = 3,
}

##############################################################################
## Request/Response structs
##############################################################################

struct StartBuildRequest {
  1: optional i64 startTimestampMillis;
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

# Analyses the presence of RuleKeys in the buckcache.
struct AnalyseRuleKeysRequest {
  1: optional list<string> ruleKeys;
}

# Returns the status of all ruleKeys in the request in the same exact order.
struct AnalyseRuleKeysResponse {
  1: optional list<RuleKeyStatus> statuses;
}

##############################################################################
## Top-Level Buck-Frontend HTTP body thrift Request/Response format
##############################################################################

enum FrontendRequestType {
  UNKNOWN = 0,
  START_BUILD = 1,
  BUILD_STATUS = 2,

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendRequest {
  1: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  2: optional StartBuildRequest startBuild;
  3: optional BuildStatusRequest buildStatus;
  6: optional AnalyseRuleKeysRequest analyseRuleKeysRequest;

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  11: optional StartBuildResponse startBuild;
  12: optional BuildStatusResponse buildStatus;
  15: optional AnalyseRuleKeysResponse analyseRuleKeysResponse;

  // [100-199] Values are reserved for the buck cache request types.
}
