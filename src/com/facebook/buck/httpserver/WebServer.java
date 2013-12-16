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

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.util.List;
import java.util.Map;

/**
 * A WebSocket server that reports events of buck.
 */
public class WebServer {

  private static final String INDEX_CONTEXT_PATH = "/";
  private static final String STATIC_CONTEXT_PATH = "/static";
  private static final String TRACE_CONTEXT_PATH = "/trace";
  private static final String TRACES_CONTEXT_PATH = "/traces";
  private static final String TRACE_DATA_CONTEXT_PATH = "/tracedata";

  private final int port;
  private final ProjectFilesystem projectFilesystem;
  private final String staticContentDirectory;
  private final Server server;
  private final StreamingWebSocketServlet streamingWebSocketServlet;

  public WebServer(int port,
      ProjectFilesystem projectFilesystem,
      String staticContentDirectory) {
    this.port = port;
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.staticContentDirectory = Preconditions.checkNotNull(staticContentDirectory);
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

    // Package up all of the handlers into a ContextHandlerCollection to serve as the handler for
    // the server.
    List<? extends Handler> handlers = createHandlers();
    ContextHandlerCollection contexts = new ContextHandlerCollection();
    contexts.setHandlers(handlers.toArray(new Handler[0]));
    server.setHandler(contexts);

    try {
      server.start();
    } catch (Exception e) {
      throw new WebServerException("Cannot start Websocket server.", e);
    }
  }

  @VisibleForTesting
  List<ContextHandler> createHandlers() {
    Map<String, Handler> contextPathToHandler = Maps.newHashMap();

    contextPathToHandler.put(INDEX_CONTEXT_PATH, new TemplateHandler(new IndexHandlerDelegate()));

    ResourceHandler resourceHandler = new ResourceHandler();
    resourceHandler.setResourceBase(staticContentDirectory);
    contextPathToHandler.put(STATIC_CONTEXT_PATH, resourceHandler);
    contextPathToHandler.put(TRACE_CONTEXT_PATH, new TemplateHandler(new TraceHandlerDelegate()));
    contextPathToHandler.put(TRACES_CONTEXT_PATH, new TemplateHandler(
        new TracesHandlerDelegate(projectFilesystem)));
    contextPathToHandler.put(TRACE_DATA_CONTEXT_PATH, new TraceDataHandler(projectFilesystem));

    ImmutableList.Builder<ContextHandler> handlers = ImmutableList.builder();
    for (Map.Entry<String, Handler> entry : contextPathToHandler.entrySet()) {
      String contextPath = entry.getKey();
      Handler handler = entry.getValue();
      ContextHandler contextHandler = new ContextHandler(contextPath);
      contextHandler.setHandler(handler);
      handlers.add(contextHandler);
    }

    // Create a handler that acts as a WebSocket server.
    ServletContextHandler servletContextHandler = new ServletContextHandler(
        /* parent */ server,
        /* contextPath */ "/comet",
        /* sessions */ true,
        /* security */ false);
    servletContextHandler.addServlet(new ServletHolder(streamingWebSocketServlet), "/echo");
    handlers.add(servletContextHandler);
    return handlers.build();
  }

  public synchronized void stop() throws WebServerException {
    if (!server.isRunning()) {
      return;
    }
    try {
      server.stop();
    } catch (Exception e) {
      throw new WebServerException("Cannot stop Websocket server.", e);
    }
  }

  @SuppressWarnings("serial")
  public class WebServerException extends Exception {

    public WebServerException(String message, Exception clause) {
      super(message, clause);
    }
  }
}
