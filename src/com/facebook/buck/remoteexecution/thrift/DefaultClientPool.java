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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Standard implementation of ClientPool
 *
 * @param <ClientType>
 */
public class DefaultClientPool<ClientType> implements ClientPool<ClientType> {
  private final BlockingQueue<ClientType> clientPool;
  private final Supplier<ClientType> clientSupplier;
  private final int maxClients;
  private final AtomicInteger clientCreationAttempts = new AtomicInteger(0);

  public DefaultClientPool(Supplier<ClientType> clientSupplier, int maxClients) {
    this.clientSupplier = clientSupplier;
    this.clientPool = new ArrayBlockingQueue<>(maxClients);
    this.maxClients = maxClients;
  }

  @Override
  public PooledClient<ClientType> getPooledClient() {
    try {
      ClientType rawClient = getOrCreateClient();
      return new PooledClient<>(rawClient, () -> releaseClientBackToPool(rawClient));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private ClientType getOrCreateClient() throws InterruptedException {
    ClientType clientFromPool = clientPool.poll();
    if (clientFromPool != null) {
      return clientFromPool;
    }
    int clientCreationAttempt = clientCreationAttempts.incrementAndGet();
    if (clientCreationAttempt <= maxClients) {
      // Create a new client to the pool, as max client count not created yet
      clientPool.put(clientSupplier.get());
    }

    // Block until a client is ready (maybe immediately) and then return it
    return clientPool.take();
  }

  // Release the client back to the pool once enclosing try block finishes
  private void releaseClientBackToPool(ClientType client) {
    try {
      clientPool.put(client);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(ex);
    }
  }
}
