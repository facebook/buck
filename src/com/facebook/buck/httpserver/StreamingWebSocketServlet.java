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

package com.facebook.buck.httpserver;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

@SuppressWarnings("serial")
public class StreamingWebSocketServlet extends WebSocketServlet {

  private static final JsonNodeFactory jsonNodeFactory = JsonNodeFactory.instance;

  // This is threadsafe
  private final Set<MyWebSocket> connections;

  public StreamingWebSocketServlet() {
    this.connections = Collections.newSetFromMap(Maps.<MyWebSocket, Boolean>newConcurrentMap());
  }

  @Override
  public void configure(WebSocketServletFactory factory) {
    // Most implementations of this method simply invoke factory.register(DispatchSocket.class);
    // however, that requires DispatchSocket to have a no-arg constructor. That does not work for
    // us because we would like all WebSockets created by this factory to have a reference to this
    // parent class. This is why we override the default WebSocketCreator for the factory.
    WebSocketCreator wrapperCreator = new WebSocketCreator() {
      @Override
      public Object createWebSocket(UpgradeRequest req, UpgradeResponse resp) {
        return new MyWebSocket();
      }
    };
    factory.setCreator(wrapperCreator);
  }

  public void tellClients(BuckEvent event) {
    if (connections.isEmpty()) {
      return;
    }

    try {
      String message = new ObjectMapper().writeValueAsString(event);
      tellAll(message);
    } catch (IOException e) {
      Throwables.propagate(e);
    }
  }

  /** Sends the message to all WebSockets that are currently connected. */
  private void tellAll(String message) {
    for (MyWebSocket webSocket : connections) {
      if (webSocket.isConnected()) {
        webSocket.getRemote().sendStringByFuture(message);
      }
    }
  }

  /** This is the httpserver component of a WebSocket that maintains a session with one client. */
  public class MyWebSocket extends WebSocketAdapter {

    @Override
    public void onWebSocketConnect(Session session) {
      super.onWebSocketConnect(session);
      connections.add(this);

      // TODO(mbolin): Record all of the events for the last build that was started. For a fresh
      // connection, replay all of the events to get the client caught up. Though must be careful,
      // as this may not be a *new* connection from the client, but a *reconnection*, in which
      // case we have to be careful about redrawing.
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
      super.onWebSocketClose(statusCode, reason);
      connections.remove(this);
    }

    @Override
    public void onWebSocketText(String message) {
      super.onWebSocketText(message);
      if (isNotConnected()) {
        return;
      }

      // TODO(mbolin): Handle requests from client instead of only pushing data down.
    }
  }

  static ObjectNode createJsonForGraph(ActionGraph graph) {
    ObjectNode nodesToDeps = jsonNodeFactory.objectNode();
    for (BuildRule source : graph.getNodes()) {
      ArrayNode deps = jsonNodeFactory.arrayNode();
      for (BuildRule sink : graph.getOutgoingNodesFor(source)) {
        deps.add(sink.getFullyQualifiedName());
      }
      nodesToDeps.put(source.getFullyQualifiedName(), deps);
    }

    return nodesToDeps;
  }
}
