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

import com.facebook.buck.core.util.log.Logger;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.thrift.TBase;

/** Implementation of the ThriftOverHttpService. */
public class ThriftOverHttpServiceImpl<
        ThriftRequest extends TBase<?, ?>, ThriftResponse extends TBase<?, ?>>
    implements ThriftOverHttpService<ThriftRequest, ThriftResponse> {

  public static final MediaType THRIFT_CONTENT_TYPE = MediaType.parse("application/x-thrift");
  public static final String THRIFT_PROTOCOL_HEADER = "X-Thrift-Protocol";

  private static final Logger LOG = Logger.get(ThriftOverHttpServiceImpl.class);

  private final ThriftOverHttpServiceConfig config;

  public ThriftOverHttpServiceImpl(ThriftOverHttpServiceConfig config) {
    this.config = config;
  }

  @Override
  public void makeRequest(ThriftRequest thriftRequest, ThriftResponse thriftResponse)
      throws IOException {

    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Making Thrift Request: [%s].", ThriftUtil.thriftToDebugJson(thriftRequest));
    }

    byte[] serializedBytes = ThriftUtil.serialize(config.getThriftProtocol(), thriftRequest);
    RequestBody body =
        RequestBody.create(THRIFT_CONTENT_TYPE, serializedBytes, 0, serializedBytes.length);
    Request.Builder requestBuilder =
        new Request.Builder()
            .addHeader(THRIFT_PROTOCOL_HEADER, config.getThriftProtocol().toString().toLowerCase())
            .post(body);

    // Make the HTTP request and handle the response status code.
    try (HttpResponse response =
        config.getService().makeRequest(config.getThriftPath(), requestBuilder)) {
      if (response.statusCode() != 200) {
        throw new IOException(
            String.format(
                "HTTP response returned unexpected status [%s:%s] from URL [%s].",
                response.statusCode(), response.statusMessage(), response.requestUrl()));
      }

      // Deserialize the body payload into the thrift struct.
      try (InputStream responseBody = response.getBody()) {
        ThriftUtil.deserialize(config.getThriftProtocol(), responseBody, thriftResponse);
      }
    }
  }

  @Override
  public void close() {
    config.getService().close();
  }
}
