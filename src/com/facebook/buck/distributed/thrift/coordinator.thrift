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
## DataTypes
##############################################################################
enum GetTargetsToBuildAction {
  UNKNOWN, // When this hasn't been set.
  BUILD_TARGETS, // Build the returned build targets.
  RETRY_LATER, // Retry requesting build targets in a bit as there is currently nothing to build.
  CLOSE_CLIENT, // Build is finished and the remote client can close.
}


##############################################################################
## Request/Response structs
##############################################################################
struct GetTargetsToBuildRequest {
  1: optional string minionId;
  2: optional stampede.StampedeId stampedeId;
}

struct GetTargetsToBuildResponse {
  1: optional GetTargetsToBuildAction action = GetTargetsToBuildAction.UNKNOWN;

  // Fully qualified name of the BuildTarget from the BuildRule (ActionGraph).
  2: optional list<string> buildTargets;
}

struct FinishedBuildingRequest {
  1: optional string minionId;
  2: optional i32 buildExitCode;
  3: optional stampede.StampedeId stampedeId;
}

struct FinishedBuildingResponse {
  1: optional bool continueBuilding;
}


##############################################################################
## Service
##############################################################################
service CoordinatorService {
  // Called by Minions to request workload to the Coordinator.
  GetTargetsToBuildResponse getTargetsToBuild(
      1:GetTargetsToBuildRequest request);

  // Called by Minions to tell the Coordinator they have just finished building their workload.
  FinishedBuildingResponse finishedBuilding(1:FinishedBuildingRequest request);


  // TODO(ruibm): Some form of heartbeat protocol needs to exist between Minions and Coordinator to
  // make sure if some Minion has silently died, the workload is picked up by a different machine.
}
