/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.slb;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LoadBalancedHttpResponseTest extends EasyMockSupport {
  private static final URI SERVER = URI.create("http://localhost/example");

  private HttpLoadBalancer mockLoadBalancer;
  private InputStream mockInputStream;
  private BufferedSource mockBufferedSource;
  private ResponseBody responseBody;
  private Response response;
  private Request request;

  @Before
  public void setUp() throws IOException {
    mockLoadBalancer = createMock(HttpLoadBalancer.class);
    mockInputStream = createMock(InputStream.class);

    mockBufferedSource = createMock(BufferedSource.class);
    mockBufferedSource.close();
    EasyMock.expectLastCall().once();

    responseBody = ResponseBody.create(MediaType.parse("text/plain"), 42, mockBufferedSource);
    request = new Request.Builder().url(SERVER.toString()).build();
    response =
        new Response.Builder()
            .body(responseBody)
            .code(200)
            .protocol(Protocol.HTTP_1_1)
            .message("")
            .request(request)
            .build();
  }

  @Test
  public void testSuccessOnlyReportedOnce() throws IOException {
    // Finish the test setup.
    mockLoadBalancer.reportRequestSuccess(EasyMock.eq(SERVER));
    EasyMock.expectLastCall().once();
    replayAll();

    // Run the test.
    LoadBalancedHttpResponse response =
        new LoadBalancedHttpResponse(SERVER, mockLoadBalancer, this.response);
    response.close();

    verifyAll();
  }

  @Test
  public void testSuccessOnlyReportedOnceEvenWithMultipleCloseCalls() throws IOException {
    // Finish the test setup.
    mockLoadBalancer.reportRequestSuccess(EasyMock.eq(SERVER));
    EasyMock.expectLastCall().once();
    mockBufferedSource.close();
    EasyMock.expectLastCall().once();
    replayAll();

    // Run the test.
    try (LoadBalancedHttpResponse response =
        new LoadBalancedHttpResponse(SERVER, mockLoadBalancer, this.response)) {
      response.close();
    }

    verifyAll();
  }

  @Test(expected = IOException.class)
  public void testInputStreamExceptionReportsToLoadBalancer() throws IOException {
    // Finish the test setup.
    EasyMock.expect(mockBufferedSource.inputStream()).andReturn(mockInputStream).once();
    EasyMock.expect(mockInputStream.read()).andThrow(new IOException()).once();
    mockLoadBalancer.reportRequestException(EasyMock.eq(SERVER));
    EasyMock.expectLastCall().once();
    replayAll();

    // Run the test.
    try (LoadBalancedHttpResponse response =
        new LoadBalancedHttpResponse(SERVER, mockLoadBalancer, this.response)) {
      response.getBody().read();
    }
    verifyAll();
  }

  @Test
  public void testMultipleExceptionsOnlyReportOnce() throws IOException {
    // Finish the test setup.
    final int exceptionCount = 42;
    EasyMock.expect(mockBufferedSource.inputStream()).andReturn(mockInputStream).once();
    EasyMock.expect(mockInputStream.read()).andThrow(new IOException()).times(exceptionCount);
    mockInputStream.close();
    EasyMock.expectLastCall().once();
    mockLoadBalancer.reportRequestException(EasyMock.eq(SERVER));
    EasyMock.expectLastCall().once();
    replayAll();

    // Run the test.
    try (LoadBalancedHttpResponse response =
        new LoadBalancedHttpResponse(SERVER, mockLoadBalancer, this.response)) {
      try (InputStream inputStream = response.getBody()) {
        for (int i = 0; i < exceptionCount; ++i) {
          try {
            inputStream.read();
          } catch (IOException e) {
            continue;
          }

          Assert.fail("An IOException should've been thrown.");
        }
      }
    }
    verifyAll();
  }
}
