/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.BuildSlaveRequest;
import com.facebook.buck.distributed.thrift.BuildSlaveResponse;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.slb.ThriftOverHttpServiceImpl;
import java.io.IOException;

/** Extension of ThriftOverHttpServiceImpl to get rid of the template arguments */
public class BuildSlaveService
    extends ThriftOverHttpServiceImpl<BuildSlaveRequest, BuildSlaveResponse> {
  private static final Logger LOG = Logger.get(BuildSlaveService.class);

  public BuildSlaveService(ThriftOverHttpServiceConfig config) {
    super(config);
  }

  /** Wrapper around function that sends a request to http server running on localhost */
  public BuildSlaveResponse makeRequest(BuildSlaveRequest request) {
    BuildSlaveResponse response = new BuildSlaveResponse();
    try {
      makeRequest(request, response);
    } catch (IOException e) {
      LOG.warn(e, "Failed to talk to local thrift service.");
      response.setWasSuccessful(false);
    }
    return response;
  }
}
