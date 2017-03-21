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
	2: optional BuildSlaveConsoleEvent consoleEvent;
}

enum ConsoleEventSeverity {
    INFO = 0,
    WARNING = 1
    SEVERE = 2,   
}

struct BuildSlaveConsoleEvent {
    1: optional stampede.RunId runId;
    2: optional string message;
    3: optional ConsoleEventSeverity severity;
}

##############################################################################
## Buck build slave status
##############################################################################

struct BuildSlaveStatus {
    1: optional stampede.RunId runId;
    2: optional i32 rulesStartedCount;
    3: optional i32 rulesFinishedCount;
    4: optional i32 totalRulesCount;
    5: optional i32 cacheHitsCount;
    6: optional i32 cacheMissesCount;
    7: optional i32 cacheIgnoresCount;
    8: optional i32 cacheLocalKeyUnchangedHitsCount;
}
