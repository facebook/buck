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

import com.squareup.okhttp.Call;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;

public class SingleUriServiceTest {
  private static final URI SERVER = URI.create("http://localhost:4242");

  @Test
  public void testClientIsCalledWithFullUrl() throws IOException, InterruptedException {
    OkHttpClient mockClient = EasyMock.createMock(OkHttpClient.class);
    String path = "/my/super/path";
    Request.Builder request = new Request.Builder()
        .url(SERVER + path)
        .get();
    Call mockCall = EasyMock.createMock(Call.class);
    EasyMock.expect(mockClient.newCall(EasyMock.anyObject(Request.class))).andReturn(mockCall);
    Response response = new Response.Builder()
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
