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
import com.facebook.buck.distributed.thrift.BuildSlaveRequestType;
import com.facebook.buck.distributed.thrift.BuildSlaveResponse;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.GetAllAvailableCapacityResponse;
import com.facebook.buck.distributed.thrift.ObtainAllAvailableCapacityResponse;
import com.facebook.buck.distributed.thrift.ObtainCapacityResponse;
import com.facebook.buck.distributed.thrift.ReturnCapacityResponse;
import java.io.IOException;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CapacityServiceTest {

  public static final int MAX_AVAILABLE_CAPACITY = 10;
  public static final int CAPACITY = 1;

  private BuildSlaveService mockService;
  private CapacityService service;

  @Before
  public void setUp() {
    mockService = EasyMock.createMock(BuildSlaveService.class);
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("test");
    service = new CapacityService(mockService, buildSlaveRunId);
  }

  @Test
  public void canGetAvailableCapacity() throws IOException {
    Capture<BuildSlaveRequest> request = EasyMock.newCapture();
    BuildSlaveResponse response = createGetResponse(MAX_AVAILABLE_CAPACITY);

    EasyMock.expect(mockService.makeRequest(EasyMock.capture(request))).andReturn(response).once();
    EasyMock.replay(mockService);

    int availableCapacity = service.getAllAvailableCapacity();
    Assert.assertEquals(MAX_AVAILABLE_CAPACITY, availableCapacity);
    Assert.assertEquals(
        BuildSlaveRequestType.GET_ALL_AVAILABLE_CAPACITY, request.getValue().getType());

    EasyMock.verify(mockService);
  }

  @Test
  public void canObtainAvailableCapacity() throws IOException {
    Capture<BuildSlaveRequest> request = EasyMock.newCapture();
    BuildSlaveResponse response = createObtainAvailableResponse(MAX_AVAILABLE_CAPACITY);

    EasyMock.expect(mockService.makeRequest(EasyMock.capture(request))).andReturn(response).once();
    EasyMock.replay(mockService);

    int availableCapacity = service.obtainAllAvailableCapacity();
    Assert.assertEquals(MAX_AVAILABLE_CAPACITY, availableCapacity);
    Assert.assertEquals(
        BuildSlaveRequestType.OBTAIN_ALL_AVAILABLE_CAPACITY, request.getValue().getType());

    EasyMock.verify(mockService);
  }

  @Test
  public void canObtainCapacity() {
    Capture<BuildSlaveRequest> obtainRequest = EasyMock.newCapture();
    BuildSlaveResponse obtainResponse = createObtainResponse();

    EasyMock.expect(mockService.makeRequest(EasyMock.capture(obtainRequest)))
        .andReturn(obtainResponse)
        .once();

    EasyMock.replay(mockService);

    service.obtainCapacity(CAPACITY);
    Assert.assertEquals(BuildSlaveRequestType.OBTAIN_CAPACITY, obtainRequest.getValue().getType());

    int obtainedCapacity = obtainRequest.getValue().obtainCapacityRequest.capacity;
    Assert.assertEquals(CAPACITY, obtainedCapacity);

    EasyMock.verify(mockService);
  }

  @Test
  public void canReturnCapacity() throws IOException {
    Capture<BuildSlaveRequest> request = EasyMock.newCapture();
    BuildSlaveResponse response = createReturnResponse();

    EasyMock.expect(mockService.makeRequest(EasyMock.capture(request))).andReturn(response).once();
    EasyMock.replay(mockService);

    service.returnCapacity(CAPACITY);
    Assert.assertEquals(BuildSlaveRequestType.RETURN_CAPACITY, request.getValue().getType());

    int returnedCapacity = request.getValue().returnCapacityRequest.capacity;
    Assert.assertEquals(CAPACITY, returnedCapacity);

    EasyMock.verify(mockService);
  }

  private BuildSlaveResponse createGetResponse(int capacity) {
    BuildSlaveResponse response = new BuildSlaveResponse();
    response.setWasSuccessful(true);

    GetAllAvailableCapacityResponse getCapacityResponse = new GetAllAvailableCapacityResponse();
    getCapacityResponse.setAvailableCapacity(capacity);
    response.getAllAvailableCapacityResponse = getCapacityResponse;

    return response;
  }

  private BuildSlaveResponse createObtainResponse() {
    BuildSlaveResponse response = new BuildSlaveResponse();
    response.setWasSuccessful(true);

    ObtainCapacityResponse obtainCapacityResponse = new ObtainCapacityResponse();
    response.obtainCapacityResponse = obtainCapacityResponse;

    return response;
  }

  private BuildSlaveResponse createObtainAvailableResponse(int capacity) {
    BuildSlaveResponse response = new BuildSlaveResponse();
    response.setWasSuccessful(true);

    ObtainAllAvailableCapacityResponse obtainCapacityResponse =
        new ObtainAllAvailableCapacityResponse();
    obtainCapacityResponse.setObtainedCapacity(capacity);
    response.obtainAllAvailableCapacityResponse = obtainCapacityResponse;

    return response;
  }

  private BuildSlaveResponse createReturnResponse() {
    BuildSlaveResponse response = new BuildSlaveResponse();
    response.setWasSuccessful(true);

    ReturnCapacityResponse returnCapacityResponse = new ReturnCapacityResponse();
    response.returnCapacityResponse = returnCapacityResponse;

    return response;
  }
}
