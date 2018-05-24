# Copyright 2016 Facebook. All Rights Reserved.
#
#!/usr/local/bin/thrift -cpp -py -java
#
# Whenever you change this file please run the following command to refresh the java source code:
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/distributed/thrift/build_slave.thrift

namespace java com.facebook.buck.distributed.thrift

include "stampede.thrift"
##############################################################################
## Buck build slave events
##############################################################################

enum BuildSlaveEventType {
    UNKNOWN = 0,
    CONSOLE_EVENT = 1,
    BUILD_RULE_STARTED_EVENT = 2,
    BUILD_RULE_FINISHED_EVENT = 3,
    ALL_BUILD_RULES_FINISHED_EVENT = 4,
    MOST_BUILD_RULES_FINISHED_EVENT = 5,
    COORDINATOR_BUILD_PROGRESS_EVENT = 6,
    BUILD_RULE_UNLOCKED_EVENT = 7,
}

struct BuildSlaveEvent {
    1: optional BuildSlaveEventType eventType = BuildSlaveEventType.UNKNOWN;
    4: optional i64 timestampMillis;

    10: optional BuildSlaveConsoleEvent consoleEvent;
    11: optional BuildRuleStartedEvent buildRuleStartedEvent;
    12: optional BuildRuleFinishedEvent buildRuleFinishedEvent;
    13: optional CoordinatorBuildProgressEvent coordinatorBuildProgressEvent;
    14: optional BuildRuleUnlockedEvent buildRuleUnlockedEvent;
}

enum ConsoleEventSeverity {
    INFO = 0,
    WARNING = 1
    SEVERE = 2,
}

struct BuildSlaveConsoleEvent {
    1: optional string message;
    2: optional ConsoleEventSeverity severity;
}

struct BuildRuleStartedEvent {
    1: optional string buildTarget;
}

struct BuildRuleFinishedEvent {
    1: optional string buildTarget;
}

struct CoordinatorBuildProgressEvent {
    1: optional CoordinatorBuildProgress buildProgress;
}

struct BuildRuleUnlockedEvent {
    1: optional string buildTarget;
}

##############################################################################
## Buck build slave status
##############################################################################

struct CacheRateStats {
    1: optional i32 cacheHitsCount;
    2: optional i32 cacheMissesCount;
    3: optional i32 cacheIgnoresCount;
    4: optional i32 cacheErrorsCount;
    5: optional i32 cacheLocalKeyUnchangedHitsCount;
    6: optional i32 unexpectedCacheMissesCount;

    10: optional i32 totalRulesCount;
    11: optional i32 updatedRulesCount;
}

struct BuildSlaveStatus {
    1: optional stampede.StampedeId stampedeId;
    2: optional stampede.BuildSlaveRunId buildSlaveRunId;

    10: optional i32 totalRulesCount;
    11: optional i32 rulesBuildingCount;
    12: optional i32 rulesFinishedCount;
    14: optional i32 rulesFailureCount;

    20: optional CacheRateStats cacheRateStats;
    21: optional i64 httpArtifactTotalBytesUploaded;
    22: optional i32 httpArtifactUploadsScheduledCount;
    23: optional i32 httpArtifactUploadsOngoingCount;
    24: optional i32 httpArtifactUploadsSuccessCount;
    25: optional i32 httpArtifactUploadsFailureCount;

    30: optional i32 filesMaterializedCount;
}

struct CoordinatorBuildProgress {
    1: optional i32 totalRulesCount;
    2: optional i32 builtRulesCount;
    3: optional i32 skippedRulesCount;
}

struct HealthCheckStats {
    1: optional i32 slowHeartbeatsReceivedCount;
    2: optional i32 heartbeatsReceivedCount;
    3: optional i64 averageHeartbeatIntervalMillis;
    4: optional i64 slowestHeartbeatIntervalMillis;
    5: optional string slowestHeartbeatMinionId;
    6: optional i32 slowDeadMinionChecksCount;
    7: optional i64 slowestDeadMinionCheckIntervalMillis;
}

struct FileMaterializationStats {
    1: optional i32 totalFilesMaterializedCount;
    2: optional i32 filesMaterializedFromCASCount;
    3: optional i64 totalTimeSpentMaterializingFilesFromCASMillis;
    4: optional i32 fullBufferCasMultiFetchCount;
    5: optional i32 periodicCasMultiFetchCount;
    6: optional i64 timeSpentInMultiFetchNetworkCallsMs;
}

struct BuildSlavePerStageTimingStats {
    1: optional i64 distBuildStateFetchTimeMillis;
    2: optional i64 distBuildStateLoadingTimeMillis;
    3: optional i64 sourceFilePreloadTimeMillis;
    4: optional i64 targetGraphDeserializationTimeMillis;
    5: optional i64 actionGraphCreationTimeMillis;
    6: optional i64 totalBuildtimeMillis;
    7: optional i64 distBuildPreparationTimeMillis;
    8: optional i64 reverseDependencyQueueCreationTimeMillis;
}

struct BuildSlaveFinishedStats {
    1: optional BuildSlaveStatus buildSlaveStatus;
    2: optional i32 exitCode;
    3: optional FileMaterializationStats fileMaterializationStats;
    4: optional BuildSlavePerStageTimingStats buildSlavePerStageTimingStats;
    5: optional string hostname;
    6: optional string distBuildMode;
    7: optional HealthCheckStats healthCheckStats;
    8: optional string jobName;
}

##############################################################################
## HTTP body thrift Request/Response structs
##############################################################################

struct GetAllAvailableCapacityRequest {
}

struct GetAllAvailableCapacityResponse {
  1 : optional i32 availableCapacity;
}

struct ObtainCapacityRequest {
  1 : optional stampede.BuildSlaveRunId buildSlaveRunId;
  2 : optional i32 capacity;
}

struct ObtainCapacityResponse {
  1 : optional i32 obtainedCapacity;
}

struct ObtainAllAvailableCapacityRequest {
  1 : optional stampede.BuildSlaveRunId buildSlaveRunId;
}

struct ObtainAllAvailableCapacityResponse {
  1 : optional i32 obtainedCapacity;
}

struct ReturnCapacityRequest {
  1 : optional stampede.BuildSlaveRunId buildSlaveRunId;
  2 : optional i32 capacity;
}

struct ReturnCapacityResponse {
}

enum BuildSlaveRequestType {
  UNKNOWN = 0,
  GET_ALL_AVAILABLE_CAPACITY = 1,
  OBTAIN_CAPACITY = 2,
  OBTAIN_ALL_AVAILABLE_CAPACITY = 3,
  RETURN_CAPACITY = 4,
}

struct BuildSlaveRequest {
  1 : optional BuildSlaveRequestType type = BuildSlaveRequestType.UNKNOWN;
  2 : optional GetAllAvailableCapacityRequest getAllAvailableCapacityRequest;
  3 : optional ObtainCapacityRequest obtainCapacityRequest;
  4 : optional ObtainAllAvailableCapacityRequest obtainAllAvailableCapacityRequest;
  5 : optional ReturnCapacityRequest returnCapacityRequest;
}

struct BuildSlaveResponse {
  1 : optional bool wasSuccessful;
  2 : optional string errorMessage;

  10 : optional BuildSlaveRequestType type = BuildSlaveRequestType.UNKNOWN;
  11 : optional GetAllAvailableCapacityResponse getAllAvailableCapacityResponse;
  12 : optional ObtainAllAvailableCapacityResponse obtainAllAvailableCapacityResponse;
  13 : optional ObtainCapacityResponse obtainCapacityResponse;
  14 : optional ReturnCapacityResponse returnCapacityResponse;
}
