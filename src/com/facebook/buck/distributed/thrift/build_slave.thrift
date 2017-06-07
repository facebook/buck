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
}

struct BuildSlaveEvent {
    1: optional BuildSlaveEventType eventType = BuildSlaveEventType.UNKNOWN;
    2: optional stampede.StampedeId stampedeId;
    3: optional stampede.RunId runId;

    10: optional BuildSlaveConsoleEvent consoleEvent;
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
    2: optional stampede.RunId runId;

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
}
