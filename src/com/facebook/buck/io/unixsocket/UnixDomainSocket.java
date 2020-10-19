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

package com.facebook.buck.io.unixsocket;

import com.facebook.buck.io.watchman.Transport;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

/** Implements a {@link Socket} backed by a junixsocket. */
public class UnixDomainSocket extends Socket implements Transport {
  private final AFUNIXSocket socket;

  /** Creates a Unix domain socket bound to a path. */
  public static UnixDomainSocket createSocketWithPath(Path path) throws IOException {
    File socketFile = path.toFile();
    AFUNIXSocket socket = AFUNIXSocket.newInstance();
    try {
      socket.connect(new AFUNIXSocketAddress(socketFile));
      return new UnixDomainSocket(socket);
    } catch (Throwable e) {
      socket.close();
      throw e;
    }
  }

  /** Creates a Unix domain socket backed by junixsocket */
  private UnixDomainSocket(AFUNIXSocket socket) {
    this.socket = socket;
  }

  @Override
  public boolean isConnected() {
    return socket.isConnected();
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return socket.getInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return socket.getOutputStream();
  }

  @Override
  public void shutdownInput() throws IOException {
    socket.shutdownInput();
  }

  @Override
  public void shutdownOutput() throws IOException {
    socket.shutdownOutput();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  @Override
  protected void finalize() throws IOException {
    close();
  }
}
