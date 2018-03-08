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

package com.facebook.buck.eden;

import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A pool of {@link EdenClient} objects. */
public final class EdenClientPool {
  /**
   * We share one {@link Clock} instance across all {@link ReconnectingEdenClient}s created by this
   * pool. Ideally, we would use the one created by {@link com.facebook.buck.cli.Main}, but it is a
   * bit of a pain to thread it all the way through to here.
   */
  private static final Clock clock = new DefaultClock();

  /**
   * Instances of {@link EdenClient} are not guaranteed to be thread-safe, so we create them via a
   * {@link ThreadLocal} rather than create a new client for each Thrift call.
   */
  private final ThreadLocal<EdenClient> clientFactory;

  private EdenClientPool(Path socketFile) {
    this(
        new ThreadLocal<EdenClient>() {
          @Override
          protected EdenClient initialValue() {
            return new ReconnectingEdenClient(socketFile, clock);
          }
        });
  }

  @VisibleForTesting
  EdenClientPool(EdenClient edenClient) {
    this(
        new ThreadLocal<EdenClient>() {
          @Override
          protected EdenClient initialValue() {
            return edenClient;
          }
        });
  }

  private EdenClientPool(ThreadLocal<EdenClient> clientFactory) {
    this.clientFactory = clientFactory;
  }

  /** @return an {@link EdenClient} that must be used on the same thread that requested it. */
  public EdenClient getClient() {
    return clientFactory.get();
  }

  public static Optional<EdenClientPool> tryToCreateEdenClientPool(Path directoryInEdenFsMount) {
    Path socketSymlink = directoryInEdenFsMount.resolve(".eden/socket");
    Path socketFile;
    try {
      socketFile = Files.readSymbolicLink(socketSymlink);
    } catch (IOException e) {
      return Optional.empty();
    }
    return newInstanceFromSocket(socketFile);
  }

  public static Optional<EdenClientPool> newInstanceFromSocket(Path socketFile) {
    // We forcibly try to create an EdenClient as a way of verifying that `socketFile` is a
    // valid UNIX domain socket for talking to Eden. If this is not the case, then we should not
    // return a new EdenClientPool.
    ReconnectingEdenClient edenClient = new ReconnectingEdenClient(socketFile, clock);
    try {
      edenClient.listMounts();
    } catch (EdenError | IOException | TException e) {
      return Optional.empty();
    }
    return Optional.of(new EdenClientPool(socketFile));
  }
}
