/*
 * Copyright 2015-present Facebook, Inc.
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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class SingleUriServiceTest {
  private static final URI SERVER = URI.create("http://localhost:4242/");

  @Test
  public void testGetFullUrlWithoutSlashes() throws MalformedURLException {
    testGetFullUrl(
        "http://localhost:4242/with/very/sneaky/path",
        "topspin",
        "http://localhost:4242/with/very/sneaky/path/topspin");
  }

  @Test
  public void testGetFullUrlWithRightSlash() throws MalformedURLException {
    testGetFullUrl(
        "http://localhost:4242/with/very/sneaky/path",
        "/topspin",
        "http://localhost:4242/with/very/sneaky/path/topspin");
  }

  @Test
  public void testGetFullUrlWithLeftSlash() throws MalformedURLException {
    testGetFullUrl(
        "http://localhost:4242/with/very/sneaky/path/",
        "topspin",
        "http://localhost:4242/with/very/sneaky/path/topspin");
  }

  @Test
  public void testGetFullUrlWithDoubleSlash() throws MalformedURLException {
    testGetFullUrl(
        "http://localhost:4242/with/very/sneaky/path/",
        "/topspin",
        "http://localhost:4242/with/very/sneaky/path/topspin");
  }

  private void testGetFullUrl(String actualBaseUrl, String actualExtraPath, String expectedUrl)
      throws MalformedURLException {
    URI baseUri = URI.create(actualBaseUrl);
    Assert.assertEquals(
        SingleUriService.getFullUrl(baseUri, actualExtraPath).toString(), expectedUrl);
  }

  @Test
  public void testClientIsCalledWithFullUrl() throws IOException {
    OkHttpClient mockClient = EasyMock.createMock(OkHttpClient.class);
    String path = "my/super/path";
    Request.Builder request = new Request.Builder().url(SERVER + path).get();
    Call mockCall = EasyMock.createMock(Call.class);
    EasyMock.expect(mockClient.newCall(EasyMock.anyObject(Request.class))).andReturn(mockCall);
    Response response =
        new Response.Builder()
            .message("my super response")
            .request(request.build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .build();
    EasyMock.expect(mockCall.execute()).andReturn(response);
    EasyMock.replay(mockCall, mockClient);
    try (SingleUriService service = new SingleUriService(SERVER, mockClient)) {
      service.makeRequest(path, request);
    }
  }
}
