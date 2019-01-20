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
# $ thrift --gen java:generated_annotations=suppress -out src-gen/ \
#   src/com/facebook/buck/log/thrift/remote_log.thrift

namespace java com.facebook.buck.log.thrift
namespace py buck.thrift.remote_log
namespace cpp2 buck.thrift.remote_log

struct VersionControlStatsRemoteLogEntry {
  1: optional string currentRevisionId;
  2: optional list<string> baseBookmarks;
  3: optional list<string> pathsChanged;
  4: optional bool pathsChangedSampled;
  5: optional i32 unsampledPathsChangedCount;
}

struct MemoryStatsRemoteLogEntry {
  1: optional i64 timeFromStartOfCommandMs;
  2: optional i64 freeMemoryBytes;
  3: optional i64 totalMemoryBytes;
  4: optional i64 timeSpentInGcMs;
  5: optional map<string, i64> currentMemoryBytesUsageByPool;
  6: optional i64 maxMemoryBytes;
}

struct ProcessStatsRemoteLogEntry {
  1: optional string executable;
  2: optional i64 memSizeBytes;
  3: optional i64 memResidentBytes;
  4: optional i64 cpuRealMs;
  5: optional i64 cpuUserMs;
  6: optional i64 cpuSysMs;
  7: optional i64 ioBytesRead;
  8: optional i64 ioBytesWritten;
  9: optional map<string, string> context;
}

struct TimeStatsRemoteLogEntry {
  1: i64 pythonTimeMs;
  2: i64 initTimeMs;
  3: i64 parseTimeMs;
  4: i64 processingTimeMs;
  5: i64 actionGraphTimeMs;
  6: i64 rulekeyTimeMs;
  7: i64 fetchTimeMs;
  8: i64 buildTimeMs;
  9: i64 installTimeMs;
}

struct ExperimentStatsRemoteLogEntry {
  1: string tag;
  2: string variant;
  3: string propertyName;
  4: optional i64 value;
  5: optional string content;
}

struct RemoteLogEntry {
  1: optional string buildUuid;

  2: optional VersionControlStatsRemoteLogEntry versionControlStats;
  3: optional MemoryStatsRemoteLogEntry memoryStats;
  4: optional ProcessStatsRemoteLogEntry processStats;
  5: optional TimeStatsRemoteLogEntry timeStats;
  6: optional ExperimentStatsRemoteLogEntry experimentStats;
}
