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

import java.io.IOException;
import java.io.InputStream;
import org.apache.thrift.TBase;

/** Handles the contents of a hybrid thrift request. */
public abstract class HybridThriftRequestHandler<ThriftRequest extends TBase<?, ?>> {
  private final ThriftRequest request;

  /** Create request that sends no out-of-band payloads. */
  public static <ThriftRequest extends TBase<?, ?>>
      HybridThriftRequestHandler<ThriftRequest> createWithoutPayloads(ThriftRequest request) {
    return new HybridThriftRequestHandler<ThriftRequest>(request) {

      @Override
      public long getTotalPayloadsSizeBytes() {
        return 0;
      }

      @Override
      public int getNumberOfPayloads() {
        return 0;
      }

      @Override
      public InputStream getPayloadStream(int index) {
        throw new IllegalStateException();
      }
    };
  }

  protected HybridThriftRequestHandler(ThriftRequest request) {
    this.request = request;
  }

  /** Get thrift request. */
  public ThriftRequest getRequest() {
    return request;
  }

  /** The sum bytes of all out-of-band payloads. */
  public abstract long getTotalPayloadsSizeBytes();

  /** Total number of payloads. */
  public abstract int getNumberOfPayloads();

  /** Fetches the data for one specific payload. */
  public abstract InputStream getPayloadStream(int index) throws IOException;
}
