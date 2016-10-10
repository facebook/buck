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
}

struct RemoteLogEntry {
  1: optional string buildUuid;

  2: optional VersionControlStatsRemoteLogEntry versionControlStats;
  3: optional MemoryStatsRemoteLogEntry memoryStats;
  4: optional ProcessStatsRemoteLogEntry processStats;
}
