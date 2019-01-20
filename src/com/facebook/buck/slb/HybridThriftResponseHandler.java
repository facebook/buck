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

package com.facebook.buck.slb;

import com.facebook.buck.util.function.ThrowingConsumer;
import java.io.IOException;
import java.io.OutputStream;
import org.apache.thrift.TBase;

/** Control struct to receive a hybrid thrift response. */
public abstract class HybridThriftResponseHandler<ThriftResponse extends TBase<?, ?>> {

  private final ThriftResponse response;

  /** Encodes a response without out-of-band payloads. */
  public static <ThriftResponse extends TBase<?, ?>>
      HybridThriftResponseHandler<ThriftResponse> createNoPayloadHandler(ThriftResponse response) {
    return createNoPayloadHandler(
        response,
        new ThrowingConsumer<ThriftResponse, IOException>() {
          @Override
          public void accept(ThriftResponse thriftResponse) {
            // no-op.
          }
        });
  }

  /** Encodes a response without out-of-band payloads. */
  public static <ThriftResponse extends TBase<?, ?>>
      HybridThriftResponseHandler<ThriftResponse> createNoPayloadHandler(
          ThriftResponse response, ThrowingConsumer<ThriftResponse, IOException> validator) {
    return new HybridThriftResponseHandler<ThriftResponse>(response) {

      @Override
      public void onResponseParsed() throws IOException {
        validator.accept(getResponse());
      }

      @Override
      public int getTotalPayloads() {
        return 0;
      }

      @Override
      public long getPayloadSizeBytes(int index) {
        throw new IllegalStateException();
      }

      @Override
      public OutputStream getStreamForPayload(int index) {
        throw new IllegalStateException();
      }
    };
  }

  protected HybridThriftResponseHandler(ThriftResponse emptyResponse) {
    this.response = emptyResponse;
  }

  /** The thrift response. */
  public ThriftResponse getResponse() {
    return response;
  }

  /** Called as soon as the thrift metadata section of the response is parsed. */
  public void onResponseParsed() throws IOException {
    // NoOp.
  }

  /** Total number of payloads expected for the current thrift response. */
  public abstract int getTotalPayloads();

  /** Size bytes of the nth payload. Must be positive. */
  public abstract long getPayloadSizeBytes(int index);

  /** Where to write the nth payload bytes to. */
  public abstract OutputStream getStreamForPayload(int index);
}
