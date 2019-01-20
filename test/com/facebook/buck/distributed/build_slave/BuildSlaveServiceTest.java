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

import com.facebook.buck.distributed.thrift.BuildSlaveRequest;
import com.facebook.buck.distributed.thrift.BuildSlaveResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import java.io.IOException;
import okhttp3.Request;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BuildSlaveServiceTest {
  private BuildSlaveService buildSlaveService;
  private HttpService mockHttpService;

  @Before
  public void setUp() {
    mockHttpService = EasyMock.createNiceMock(HttpService.class);
    buildSlaveService = new BuildSlaveService(ThriftOverHttpServiceConfig.of(mockHttpService));
  }

  @Test
  public void testHandlesException() throws IOException {
    EasyMock.expect(
            mockHttpService.makeRequest(
                EasyMock.anyString(), EasyMock.anyObject(Request.Builder.class)))
        .andThrow(new IOException());
    EasyMock.replay(mockHttpService);

    BuildSlaveResponse response = buildSlaveService.makeRequest(new BuildSlaveRequest());
    Assert.assertFalse(response.wasSuccessful);
  }
}
