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

import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.EdenService;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.thrift.TException;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
import com.facebook.thrift.transport.TTransportException;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Client of Eden's fbthrift API. Note that there should be at most one Eden client per machine,
 * though it may have zero or more mount points.
 */
public final class EdenClient {
  /**
   * Instances of {@link com.facebook.eden.thrift.EdenService.Client} are not thread-safe, so we
   * create them via a {@link ThreadLocal} rather than create a new client for each Thrift call.
   */
  private final ThreadLocal<EdenService.Client> clientFactory;

  /**
   * @param clientFactory creates one Thrift client per thread through which Eden Thrift API calls
   *     should be made.
   */
  private EdenClient(ThreadLocal<EdenService.Client> clientFactory) {
    this.clientFactory = clientFactory;
  }

  @VisibleForTesting
  EdenClient(final EdenService.Client client) {
    this(
        new ThreadLocal<EdenService.Client>() {
          @Override
          protected EdenService.Client initialValue() {
            return client;
          }
        });
  }

  public static Optional<EdenClient> newInstance() {
    // The default path for the Eden socket is ~/local/.eden/socket.
    Path socketFile = Paths.get(System.getProperty("user.home"), "local/.eden/socket");
    return newInstance(socketFile);
  }

  private static Optional<EdenClient> newInstance(final Path socketFile) {
    ThreadLocal<EdenService.Client> clientFactory =
        new ThreadLocal<EdenService.Client>() {
          /**
           * @return {@code null} if there is no instance of Eden to connect to because this method
           *     cannot throw checked exceptions to signal a failure.
           */
          @Override
          @Nullable
          protected EdenService.Client initialValue() {
            TTransport transport;
            try {
              UnixDomainSocket socket = UnixDomainSocket.createSocketWithPath(socketFile);
              transport = new TSocket(socket);
            } catch (IOException | TTransportException e) {
              return null;
            }
            // No need to invoke transport.open() because the UnixDomainSocket is already connected.
            TProtocol protocol = new TBinaryProtocol(transport);
            return new EdenService.Client(protocol);
          }
        };

    // We forcibly try to create an EdenService.Client as a way of verifying that `socketFile` is a
    // valid UNIX domain socket for talking to Eden. If this is not the case, then we should not
    // return a new EdenClient.
    EdenService.Client client = clientFactory.get();
    if (client != null) {
      return Optional.of(new EdenClient(clientFactory));
    } else {
      return Optional.empty();
    }
  }

  public List<MountInfo> getMountInfos() throws EdenError, TException {
    return clientFactory.get().listMounts();
  }

  /** @return an Eden mount point if {@code projectRoot} is backed by Eden or {@code null}. */
  @Nullable
  public EdenMount getMountFor(Path projectRoot) throws EdenError, TException {
    for (MountInfo info : getMountInfos()) {
      // Note that we cannot use Paths.get() here because that will break unit tests where we mix
      // java.nio.file.Path and Jimfs Path objects.
      Path mountPoint = projectRoot.getFileSystem().getPath(info.mountPoint);
      if (projectRoot.startsWith(mountPoint)) {
        return new EdenMount(clientFactory, mountPoint, projectRoot);
      }
    }
    return null;
  }
}
