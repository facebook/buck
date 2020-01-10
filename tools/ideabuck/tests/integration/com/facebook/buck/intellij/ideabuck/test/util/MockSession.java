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

package com.facebook.buck.intellij.ideabuck.test.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/** Created by theodordidii on 6/1/16. */
public class MockSession implements Session {
  @Override
  public void close() {}

  @Override
  public void close(CloseStatus closeStatus) {}

  @Override
  public void close(int i, String s) {}

  @Override
  public void disconnect() throws IOException {}

  @Override
  public long getIdleTimeout() {
    return 0;
  }

  @Override
  public InetSocketAddress getLocalAddress() {
    return null;
  }

  @Override
  public WebSocketPolicy getPolicy() {
    return null;
  }

  @Override
  public String getProtocolVersion() {
    return null;
  }

  @Override
  public RemoteEndpoint getRemote() {
    return null;
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return null;
  }

  @Override
  public UpgradeRequest getUpgradeRequest() {
    return null;
  }

  @Override
  public UpgradeResponse getUpgradeResponse() {
    return null;
  }

  @Override
  public boolean isOpen() {
    return false;
  }

  @Override
  public boolean isSecure() {
    return false;
  }

  @Override
  public void setIdleTimeout(long l) {}

  @Override
  public SuspendToken suspend() {
    return null;
  }
}
