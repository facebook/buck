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

package com.facebook.buck.rules.modern.builders.thrift;

import com.facebook.thrift.async.TAsyncClient;
import com.facebook.thrift.async.TAsyncClientFactory;
import com.facebook.thrift.transport.TNonblockingTransport;
import com.facebook.thrift.transport.TTransportException;
import java.io.IOException;

/**
 * Thin wrapper around {@link TAsyncClientFactory} to take away the job of providing a new transport
 * for every new creation of {@link TAsyncClient}.
 */
public abstract class ThriftAsyncClientFactory<T extends TAsyncClient> {

  private final TAsyncClientFactory<T> internalClientFactory;

  public ThriftAsyncClientFactory(TAsyncClientFactory<T> internalClientFactory) {
    this.internalClientFactory = internalClientFactory;
  }

  protected abstract TNonblockingTransport createTransport()
      throws IOException, TTransportException;

  public T getAsyncClient() throws IOException, TTransportException {
    return internalClientFactory.getAsyncClient(createTransport());
  }
}
