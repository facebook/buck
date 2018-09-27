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
package com.facebook.buck.remoteexecution.thrift;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper around a client of type ClientType that will automatically be put back in the pool it was
 * taken from when close() is called.
 *
 * @param <ClientType>
 */
public class PooledClient<ClientType> implements AutoCloseable {
  private final ClientType client;
  private Runnable releaseFunction;
  private final AtomicBoolean released = new AtomicBoolean(false);

  public PooledClient(ClientType client, Runnable releaseFunction) {
    this.client = client;
    this.releaseFunction = releaseFunction;
  }

  public ClientType getRawClient() {
    return client;
  }

  @Override
  public void close() {
    boolean firstCloseCall = released.compareAndSet(false, true);
    if (!firstCloseCall) {
      return; // Already released
    }

    releaseFunction.run();
  }
}
