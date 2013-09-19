/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.commands;

import com.facebook.buck.plugin.intellij.commands.event.Event;
import com.facebook.buck.plugin.intellij.commands.event.EventFactory;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.net.URI;
import java.net.URISyntaxException;

public class SocketClient {

  private static final Logger LOG = Logger.getInstance(SocketClient.class);

  private final BuckPluginEventListener listener;
  private final URI echoUri;
  private WebSocketClient client;
  private ClientUpgradeRequest request;
  private DefaultWebSocket socket;

  SocketClient(int port, BuckPluginEventListener listener) {
    String address = "ws://localhost:" + port + "/comet/echo";
    try {
      echoUri = new URI(address);
    } catch (URISyntaxException e) {
      throw Throwables.propagate(e);
    }
    this.listener = Preconditions.checkNotNull(listener);
  }

  public void start() {
    try {
      client = new WebSocketClient();
      request = new ClientUpgradeRequest();
      socket = new DefaultWebSocket();
      client.start();
      client.connect(socket, echoUri, request);
    } catch (Exception e) {
      LOG.error(e);
    }
  }

  public void stop() {
    try {
      client.stop();
    } catch (Exception e) {
      LOG.error(e);
    }
  }

  private void dispatch(JsonObject object) {
    Event event = EventFactory.factory(object);
    if (event != null) {
      listener.onEvent(event);
    }
  }

  public interface BuckPluginEventListener {
    public void onEvent(Event event);
  }

  @WebSocket
  // This class must be public because WebSocketClient from Jetty need to read the annotations of
  // this class and dispatch messages received using reflection.
  public class DefaultWebSocket {

    public DefaultWebSocket() {
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
      JsonParser parser = new JsonParser();
      JsonElement json = parser.parse(msg);
      dispatch(json.getAsJsonObject());
    }
  }
}
