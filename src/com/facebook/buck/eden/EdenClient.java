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
import com.facebook.eden.EdenError;
import com.facebook.eden.EdenService;
import com.facebook.eden.MountInfo;
import com.facebook.thrift.TException;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Client of Eden's fbthrift API. Note that there should be at most one Eden client per machine,
 * though it may have zero or more mount points.
 */
public final class EdenClient {
  private final EdenService.Client client;

  /**
   * @param client through which Eden Thrift API calls should be made.
   */
  @VisibleForTesting
  EdenClient(EdenService.Client client) {
    this.client = client;
  }

  public static EdenClient newInstance() throws IOException, TException {
    // The default path for the Eden socket is ~/local/.eden/socket.
    Path socketFile = Paths.get(
        System.getProperty("user.home"),
        "local/.eden/socket");
    return newInstance(socketFile);
  }

  private static EdenClient newInstance(Path socketFile) throws IOException, TException {
    UnixDomainSocket socket = UnixDomainSocket.createSocketWithPath(socketFile);
    TTransport transport = new TSocket(socket);
    // No need to invoke transport.open() because the UnixDomainSocket is already connected.
    TProtocol protocol = new TBinaryProtocol(transport);
    EdenService.Client client = new EdenService.Client(protocol);
    return new EdenClient(client);
  }

  public List<MountInfo> getMountInfos() throws EdenError, TException {
    return client.listMounts();
  }

  /**
   * @return an Eden mount point if {@code projectRoot} is backed by Eden or {@code null}.
   */
  @Nullable
  public EdenMount getMountFor(Path projectRoot) throws EdenError, TException {
    for (MountInfo info : getMountInfos()) {
      // Note that we cannot use Paths.get() here because that will break unit tests where we mix
      // java.nio.file.Path and Jimfs Path objects.
      Path mountPoint = projectRoot.getFileSystem().getPath(info.mountPoint);
      if (projectRoot.startsWith(mountPoint)) {
        return new EdenMount(client, mountPoint, projectRoot);
      }
    }
    return null;
  }
}
