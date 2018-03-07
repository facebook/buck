/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.slb;

import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Request;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ThriftOverHttpServiceTest {

  private HttpService httpService;
  private ThriftOverHttpServiceConfig config;
  private ThriftOverHttpService<FrontendRequest, FrontendResponse> service;

  @Before
  public void setUp() {
    httpService = EasyMock.createMock(HttpService.class);
    config = ThriftOverHttpServiceConfig.builder().setService(httpService).build();
    service = new ThriftOverHttpService<FrontendRequest, FrontendResponse>(config);
  }

  @Test
  public void testSendValidMessageAndReturnError() throws IOException {
    // TODO(ruibm): Add jetty end to end integration tests for this API.
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);

    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.START_BUILD);

    Capture<Request.Builder> requestBuilder = EasyMock.newCapture();
    HttpResponse httpResponse = EasyMock.createMock(HttpResponse.class);
    EasyMock.expect(httpResponse.statusCode()).andReturn(404).atLeastOnce();
    EasyMock.expect(httpResponse.statusMessage()).andReturn("topspin").atLeastOnce();
    EasyMock.expect(httpResponse.requestUrl()).andReturn("super url").atLeastOnce();
    EasyMock.expect(
            httpService.makeRequest(EasyMock.eq("/thrift"), EasyMock.capture(requestBuilder)))
        .andReturn(httpResponse)
        .times(1);

    EasyMock.replay(httpResponse, httpService);
    try {
      service.makeRequest(request, response);
      Assert.fail("This should've thrown an IOException.");
    } catch (IOException e) {
      Assert.assertNotNull(e);
    }

    Request actualHttpRequest = requestBuilder.getValue().url("http://localhost").build();
    Assert.assertEquals(
        ThriftOverHttpService.THRIFT_CONTENT_TYPE, actualHttpRequest.body().contentType());

    EasyMock.verify(httpResponse, httpService);
  }

  @Test
  public void testSendValidMessageAndReturnValidResponse() throws IOException, TException {
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);

    FrontendResponse expectedResponse = new FrontendResponse();
    expectedResponse.setType(FrontendRequestType.START_BUILD);

    Capture<Request.Builder> requestBuilder = EasyMock.newCapture();
    TSerializer serializer = new TSerializer(config.getThriftProtocol().getFactory());
    byte[] responseBuffer = serializer.serialize(expectedResponse);
    HttpResponse httpResponse =
        new HttpResponse() {
          @Override
          public int statusCode() {
            return 200;
          }

          @Override
          public String statusMessage() {
            return "super cool msg";
          }

          @Override
          public long contentLength() {
            return responseBuffer.length;
          }

          @Override
          public InputStream getBody() {
            return new ByteArrayInputStream(responseBuffer);
          }

          @Override
          public String requestUrl() {
            return "super url";
          }

          @Override
          public void close() {
            // do nothing.
          }
        };

    EasyMock.expect(
            httpService.makeRequest(EasyMock.eq("/thrift"), EasyMock.capture(requestBuilder)))
        .andReturn(httpResponse)
        .times(1);

    EasyMock.replay(httpService);

    FrontendResponse actualResponse = new FrontendResponse();
    service.makeRequest(request, actualResponse);

    Assert.assertEquals(expectedResponse, actualResponse);
    EasyMock.verify(httpService);
  }
}
