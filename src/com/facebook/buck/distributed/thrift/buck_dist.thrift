# Copyright 2016 Facebook. All Rights Reserved.
#
#!/usr/local/bin/thrift -cpp -py -java
#
# This .thrift file contains the protocol required by the buck client to
# communicate with the buck-frontend server.
# This protocol is under active development and
# will likely be changed in non-compatible ways

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

struct BuildJobState {
  1: optional BuildJobStateBuckConfig buckConfig;
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

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  11: optional StartBuildResponse startBuild;
  12: optional BuildStatusResponse buildStatus;

  // [100-199] Values are reserved for the buck cache request types.
}
