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

package com.facebook.buck.intellij.ideabuck.ws;

import com.facebook.buck.intellij.ideabuck.ws.buckevents.BuckEventsHandlerInterface;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

// Set the maximum message text size to 24 MB
@WebSocket(maxTextMessageSize = 24 * 1024 * 1024)
public class BuckSocket {
  private AtomicBoolean connected;
  private BuckEventsHandlerInterface eventsHandler;

  @SuppressWarnings("unused")
  private Session session;

  public BuckSocket(BuckEventsHandlerInterface eventsHandler) {
    this.connected = new AtomicBoolean(false);
    this.eventsHandler = eventsHandler;
  }

  public boolean isConnected() {
    return connected.get();
  }

  @OnWebSocketClose
  public void onClose(int statusCode, String reason) {
    connected.set(false);
    this.session = null;
    eventsHandler.onDisconnect();
  }

  @OnWebSocketConnect
  public void onConnect(Session session) {
    connected.set(true);
    this.session = session;
    eventsHandler.onConnect();
  }

  @OnWebSocketMessage
  public void onMessage(String msg) {
    eventsHandler.onMessage(msg);
  }
}
