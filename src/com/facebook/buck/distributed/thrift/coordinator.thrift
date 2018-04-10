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
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/distributed/thrift/coordinator.thrift

namespace java com.facebook.buck.distributed.thrift

include "stampede.thrift"

##############################################################################
## Request/Response structs
##############################################################################

// A work unit is a list of nodes that form a chain within the build graph.
// The list is reverse dependency ordered, and nodes depend on nothing else besides
// those earlier in the list. There were either no other dependencies, or the
// other dependencies have already been built.
struct WorkUnit {
  1: optional list<string> buildTargets;
}

struct GetWorkRequest {
  1: optional string minionId;

  2: optional stampede.MinionType minionType;

  3: optional stampede.StampedeId stampedeId;

  // If a build had just finished, include the exit code.
  4: optional i32 lastExitCode;

  // All build targets that the minion has finished building (since last request),
  // and that have completed their upload to the cache.
  5: optional list<string> finishedTargets;

  // Note: a node finishing, doesn't mean the work unit it was part of is finished
  // and as a result the corresponding number of nodes to fetch might be zero.
  // Similarly we might have some left over capacity from a previous core that finished
  // and got no work from the coordinator.
  6: optional i32 maxWorkUnitsToFetch;
}

struct GetWorkResponse {
  1: optional bool continueBuilding;
  2: optional list<WorkUnit> workUnits;
}

struct ReportMinionAliveRequest {
  1: optional string minionId;
  2: optional stampede.StampedeId stampedeId;
}

struct ReportMinionAliveResponse {
}

##############################################################################
## Service
##############################################################################
service CoordinatorService {
  // Called by Minions to request work from the Coordinator.
  GetWorkResponse getWork(1:GetWorkRequest request);

  // Called regularly by minions to report to the Coordinator they are still alive.
  ReportMinionAliveResponse reportMinionAlive(1:ReportMinionAliveRequest request);
}
