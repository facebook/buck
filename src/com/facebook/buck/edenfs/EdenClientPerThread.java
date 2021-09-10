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
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;
import java.util.Optional;

/** A pool of {@link EdenClient} objects. */
public final class EdenClientPerThread {
  /**
   * We share one {@link Clock} instance across all {@link ReconnectingEdenClient}s created by this
   * pool. Ideally, we would use the one created by {@link com.facebook.buck.cli.MainRunner}, but it
   * is a bit of a pain to thread it all the way through to here.
   */
  private static final Clock clock = new DefaultClock();

  /**
   * Instances of {@link EdenClient} are not guaranteed to be thread-safe, so we create them via a
   * {@link ThreadLocal} rather than create a new client for each Thrift call.
   */
  private final ThreadLocal<EdenClientResource> clientFactory;

  private static final Logger LOG = Logger.get(EdenClientPerThread.class);

  private EdenClientPerThread(Path socketFile) {
    this(
        new ThreadLocal<EdenClientResource>() {
          @Override
          protected EdenClientResource initialValue() {
            return new ReconnectingEdenClient(socketFile, clock);
          }
        });
  }

  @VisibleForTesting
  EdenClientPerThread(EdenClientResource edenClient) {
    this(
        new ThreadLocal<EdenClientResource>() {
          @Override
          protected EdenClientResource initialValue() {
            return edenClient;
          }
        });
  }

  private EdenClientPerThread(ThreadLocal<EdenClientResource> clientFactory) {
    this.clientFactory = clientFactory;
  }

  /** @return an {@link EdenClient} that must be used on the same thread that requested it. */
  public EdenClient getClient() {
    return clientFactory.get().getEdenClient();
  }

  /**
   * Create a client connecting to a pool or return {@link Optional#empty()} if eden is not
   * available.
   */
  public static Optional<EdenClientPerThread> tryToCreateEdenClientPool(
      Path directoryInEdenFsMount) {
    Optional<Path> socket = EdenUtil.getPathFromEdenConfig(directoryInEdenFsMount, "socket");
    if (!socket.isPresent()) {
      LOG.debug("could not find the Eden socket file from the directory:" + directoryInEdenFsMount);
      return Optional.empty();
    } else {
      return newInstanceFromSocket(socket.get());
    }
  }

  /** Create clients connecting to given socket path. */
  public static Optional<EdenClientPerThread> newInstanceFromSocket(Path socketFile) {
    // We forcibly try to create an EdenClient as a way of verifying that `socketFile` is a
    // valid UNIX domain socket for talking to Eden. If this is not the case, then we should not
    // return a new EdenClientPool.
    if (EdenUtil.pingSocket(socketFile)) {
      return Optional.of(new EdenClientPerThread(socketFile));
    } else {
      return Optional.empty();
    }
  }
}
