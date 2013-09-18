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

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * A WebSocket server that reports events of buck.
 */
public class WebServer {

  private final int port;
  private final Server server;
  private final StreamingWebSocketServlet streamingWebSocketServlet;

  public WebServer(int port) {
    this.port = port;
    this.server = new Server(port);
    this.streamingWebSocketServlet = new StreamingWebSocketServlet();
  }

  public int getPort() {
    return port;
  }

  public WebServerBuckEventListener createListener() {
    return new WebServerBuckEventListener(this);
  }

  public StreamingWebSocketServlet getStreamingWebSocketServlet() {
    return streamingWebSocketServlet;
  }

  public synchronized void start() throws WebServerException {
    if (server.isStarted()) {
      return;
    }

    // Create a handler that acts as a WebSocket server.
    ServletContextHandler servletContextHandler = new ServletContextHandler(
        /* parent */ server,
        /* contextPath */ "/comet",
        /* sessions */ true,
        /* security */ false);
    servletContextHandler.addServlet(new ServletHolder(streamingWebSocketServlet), "/echo");

    // Package up all of the handlers into a ContextHandlerCollection to serve as the handler for
    // the server.
    ContextHandlerCollection contexts = new ContextHandlerCollection();
    Handler[] handlers = new Handler[] {servletContextHandler};
    contexts.setHandlers(handlers);
    server.setHandler(contexts);
    try {
      server.start();
    } catch (Exception e) {
      throw new WebServerException("Can not start Websocket server.", e);
    }
  }

  public synchronized void stop() throws WebServerException {
    if (!server.isRunning()) {
      return;
    }
    try {
      server.stop();
    } catch (Exception e) {
      throw new WebServerException("Can not stop Websocket server.", e);
    }
  }

  public class WebServerException extends Exception {

    public WebServerException(String message, Exception clause) {
      super(message, clause);
    }
  }
}
