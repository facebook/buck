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

import com.facebook.buck.log.Logger;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TSimpleJSONProtocol;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TTransport;

import java.io.IOException;

public class ThriftOverHttpService
    <ThriftRequest extends TBase<?, ?>, ThriftResponse extends TBase<?, ?>>
    implements ThriftService<ThriftRequest, ThriftResponse> {

  public static final MediaType THRIFT_CONTENT_TYPE = MediaType.parse("application/x-thrift");
  public static final String THRIFT_PROTOCOL_HEADER = "X-Thrift-Protocol";

  private static final Logger LOG = Logger.get(ThriftOverHttpService.class);

  private final ThriftOverHttpServiceConfig config;

  public ThriftOverHttpService(ThriftOverHttpServiceConfig config) {
    this.config = config;
  }

  @Override
  public void makeRequest(ThriftRequest thriftRequest, ThriftResponse thriftResponse)
      throws IOException {

    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Making Thrift Request: [%s].", thriftToDebugJson(thriftRequest));
    }

    // Prepare the request.
    // TODO(ruibm): Move to use a TTransport that won't need to keep both serialized and raw
    // data in memory.
    TSerializer serializer = new TSerializer(config.getThriftProtocol().getFactory());
    byte[] serializedBytes = null;
    try {
      serializedBytes = serializer.serialize(thriftRequest);
    } catch (TException e) {
      throw new ThriftServiceException("Problems serializing the thrift struct.", e);
    }

    RequestBody body = RequestBody.create(
        THRIFT_CONTENT_TYPE,
        serializedBytes,
        0,
        serializedBytes.length);
    Request.Builder requestBuilder = new Request.Builder()
        .addHeader(
            THRIFT_PROTOCOL_HEADER,
            config.getThriftProtocol().toString().toLowerCase())
        .post(body);


    // Make the HTTP request and handle the response status code.
    try (HttpResponse response = config.getService().makeRequest(
        config.getThriftPath(), requestBuilder)) {
      if (response.code() != 200) {
        throw new ThriftServiceException(
            String.format(
                "HTTP response returned unexpected status code [%d] from URL [%s].",
                response.code(),
                response.requestUrl()));
      }

      // Deserialize the body payload into the thrift struct.
      try (TIOStreamTransport responseTransport = new TIOStreamTransport(response.getBody())) {
        TProtocol responseProtocol =
            newProtocolInstance(config.getThriftProtocol(), responseTransport);
        thriftResponse.read(responseProtocol);
        if (LOG.isVerboseEnabled()) {
          LOG.debug("Received Thrift Response: [%s].", thriftToDebugJson(thriftResponse));
        }

      } catch (TException e) {
        throw new ThriftServiceException("Failed to deserialize the thrift body payload.", e);
      }
    }
  }

  @Override
  public void close() throws IOException {
    config.getService().close();
  }

  public static TProtocol newProtocolInstance(ThriftProtocol protocol, TTransport transport) {
    return protocol.getFactory().getProtocol(transport);
  }

  public static String thriftToDebugJson(TBase<?, ?> thriftObject) {
    TSerializer serializer = new TSerializer(new TSimpleJSONProtocol.Factory());
    try {
      return new String(serializer.serialize(thriftObject));
    } catch (TException e) {
      LOG.error(e, "Failed trying to serialize to debug JSON.");
      return "FAILED_TO_DESERIALIZE";
    }
  }
}
