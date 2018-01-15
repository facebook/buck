/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.CoordinatorService;
import com.facebook.buck.distributed.thrift.GetWorkRequest;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.ReportMinionAliveRequest;
import com.facebook.buck.distributed.thrift.ReportMinionAliveResponse;
import com.google.common.collect.Lists;
import org.apache.thrift.TException;

/**
 * Handles Coordinator requests while the build is still idle waiting for everything to be
 * initialized.
 */
public class IdleCoordinatorService implements CoordinatorService.Iface {

  @Override
  public GetWorkResponse getWork(GetWorkRequest request) {
    // Keep the Minions alive while the Coordinator is starting up.
    GetWorkResponse response = new GetWorkResponse();
    response.setContinueBuilding(true);
    response.setWorkUnits(Lists.newArrayList());
    return response;
  }

  @Override
  public ReportMinionAliveResponse reportMinionAlive(ReportMinionAliveRequest request)
      throws TException {
    return new ReportMinionAliveResponse();
  }
}
