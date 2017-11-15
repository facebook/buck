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
    BUILD_RULE_FINISHED_EVENT = 2,
}

struct BuildSlaveEvent {
    1: optional BuildSlaveEventType eventType = BuildSlaveEventType.UNKNOWN;
    2: optional stampede.StampedeId stampedeId;
    3: optional stampede.BuildSlaveRunId buildSlaveRunId;

    10: optional BuildSlaveConsoleEvent consoleEvent;
    11: optional BuildRuleFinishedEvent buildRuleFinishedEvent;
}

enum ConsoleEventSeverity {
    INFO = 0,
    WARNING = 1
    SEVERE = 2,
}

struct BuildSlaveConsoleEvent {
    1: optional string message;
    2: optional ConsoleEventSeverity severity;
    3: optional i64 timestampMillis;
}

struct BuildRuleFinishedEvent {
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

    10: optional i32 totalRulesCount;
    11: optional i32 updatedRulesCount;
}

struct BuildSlaveStatus {
    1: optional stampede.StampedeId stampedeId;
    2: optional stampede.BuildSlaveRunId buildSlaveRunId;

    10: optional i32 totalRulesCount;
    11: optional i32 rulesStartedCount;
    12: optional i32 rulesFinishedCount;
    13: optional i32 rulesSuccessCount;
    14: optional i32 rulesFailureCount;

    20: optional CacheRateStats cacheRateStats;
    21: optional i64 httpArtifactTotalBytesUploaded;
    22: optional i32 httpArtifactUploadsScheduledCount;
    23: optional i32 httpArtifactUploadsOngoingCount;
    24: optional i32 httpArtifactUploadsSuccessCount;
    25: optional i32 httpArtifactUploadsFailureCount;

    30: optional i32 filesMaterializedCount;
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
}

struct BuildSlaveFinishedStats {
    1: optional BuildSlaveStatus buildSlaveStatus;
    2: optional i32 exitCode;
    3: optional FileMaterializationStats fileMaterializationStats;
    4: optional BuildSlavePerStageTimingStats buildSlavePerStageTimingStats;
    5: optional string hostname;
    6: optional string distBuildMode;
}
