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

import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.eden.thrift.EdenService;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
import java.io.IOException;
import java.nio.file.Path;

/** Connected eden client and a reference to the open socket. */
public class EdenClientResourceImpl implements EdenClientResource {
  private final UnixDomainSocket socket;
  private final EdenClient edenClient;

  public EdenClientResourceImpl(UnixDomainSocket socket, EdenClient edenClient) {
    this.socket = socket;
    this.edenClient = edenClient;
  }

  @Override
  public EdenClient getEdenClient() {
    return edenClient;
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  /** Create a client connected to the given path. */
  public static EdenClientResourceImpl connect(Path socketFile) throws IOException {
    // Creates a new EdenService.Client by creating a new connection via the socketFile.
    UnixDomainSocket socket = UnixDomainSocket.createSocketWithPath(socketFile);
    TTransport transport = new TSocket(socket);
    // No need to invoke transport.open() because the UnixDomainSocket is already connected.
    TProtocol protocol = new TBinaryProtocol(transport);
    EdenService.Client client = new EdenService.Client(protocol);
    return new EdenClientResourceImpl(socket, new EdenClientImpl(client));
  }
}
