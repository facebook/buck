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

package com.facebook.buck.edenfs;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Pool of eden connections. */
public class EdenClientResourcePool implements AutoCloseable {

  private static final Logger LOG = Logger.get(EdenProjectFilesystemDelegate.class);

  /**
   * Now we create a new resource if pool does not have enough.
   *
   * <p>Practically it should return reconnecting client.
   */
  public interface Factory {
    EdenClientResource newClient();
  }

  /** Connections currently not in use. */
  @VisibleForTesting
  final ConcurrentLinkedQueue<EdenClientResource> pool = new ConcurrentLinkedQueue<>();

  private volatile boolean closed = false;
  private final Factory factory;

  public EdenClientResourcePool(Factory factory) {
    this.factory = factory;
  }

  private class ResourceImpl implements EdenClientResource {
    /** Null when closed. */
    private final AtomicReference<EdenClientResource> actualResource;

    private ResourceImpl(EdenClientResource actualResource) {
      this.actualResource = new AtomicReference<>(actualResource);
    }

    @Override
    public EdenClient getEdenClient() {
      EdenClientResource actualResource = this.actualResource.get();
      if (actualResource == null) {
        throw new IllegalStateException("closed");
      }
      return actualResource.getEdenClient();
    }

    /** Return the resource to the pool on close. */
    @Override
    public void close() throws IOException {
      EdenClientResource actualResource = this.actualResource.getAndSet(null);
      if (actualResource != null) {
        pool.add(actualResource);
        if (closed) {
          EdenClientResourcePool.this.close();
        }
      }
    }
  }

  /** Get a client from the pool (or create a fresh one). */
  public EdenClientResource openClient() {
    EdenClientResource actualResource = pool.poll();

    if (actualResource == null) {
      actualResource = factory.newClient();
    }

    if (closed) {
      pool.add(actualResource);
      throw new IllegalStateException("closed");
    }
    return new ResourceImpl(actualResource);
  }

  @Override
  public void close() throws IOException {
    closed = true;
    EdenClientResource resource;
    while ((resource = pool.poll()) != null) {
      try {
        resource.close();
      } catch (Exception e) {
        LOG.warn(e, "failed to close the resource");
      }
    }
  }

  public static EdenClientResourcePool newPool(Path unixSocket) {
    return new EdenClientResourcePool(
        () -> new ReconnectingEdenClient(unixSocket, new DefaultClock()));
  }
}
