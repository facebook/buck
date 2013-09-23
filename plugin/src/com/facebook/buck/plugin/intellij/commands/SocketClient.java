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
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;

public class SocketClient {

  private static final Logger LOG = Logger.getInstance(SocketClient.class);

  private final BuckPluginEventListener listener;
  private final URI echoUri;
  private WebSocketClient client;

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
    client = new DefaultWebSocketClient(echoUri, new Draft_17());
    try {
      client.connectBlocking();
    } catch (InterruptedException e) {
      LOG.error(e);
    }
  }

  public void stop() {
    client.close();
  }

  private void dispatch(String message) {
    JsonParser parser = new JsonParser();
    JsonElement json = parser.parse(message);
    if (!json.isJsonObject()) {
      LOG.error(String.format("Invalid JSON object: %s", json.toString()));
      return;
    }
    Event event = EventFactory.factory(json.getAsJsonObject());
    if (event != null) {
      listener.onEvent(event);
    }
  }

  public interface BuckPluginEventListener {
    public void onEvent(Event event);
  }

  private class DefaultWebSocketClient extends WebSocketClient {

    public DefaultWebSocketClient(URI echoUri, Draft draft) {
      super(echoUri, draft);
    }

    @Override
    public void onMessage(String message) {
      dispatch(message);
    }

    @Override
    public void onError(Exception e) {
      LOG.error(e);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
      LOG.info(String.format("Websocket opened: %s", handshake.toString()));
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      LOG.info(String.format("Websocket closed: %d %s", code, reason));
    }
  }
}
