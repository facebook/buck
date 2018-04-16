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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.trace.BuildTraces;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * A WebSocket server that reports events of buck.
 *
 * <p>The WebServer can be modeled as: a) a network listener/dispatcher which sits on the port and
 * listens for incoming http requests. b) static handlers - list of handlers that are bound to the
 * current process (list of traces/logs/socket), c) 'normal' handlers, bound to the daemon state.
 *
 * <p>We would like the server to go down as infrequently as possible (this makes it simpler for /ws
 * users who would otherwise need to reconnect and potentially lose state) which is the reason for
 * section c) above.
 */
public class WebServer {

  private static final String INDEX_CONTEXT_PATH = "/";
  private static final String ARTIFACTS_CONTEXT_PATH = "/artifacts";
  private static final String STATIC_CONTEXT_PATH = "/static";
  private static final String TRACE_CONTEXT_PATH = "/trace";
  private static final String TRACES_CONTEXT_PATH = "/traces";
  private static final String TRACE_DATA_CONTEXT_PATH = "/tracedata";

  private Optional<Integer> port;
  private final ProjectFilesystem projectFilesystem;
  private final Server server;
  private final StreamingWebSocketServlet streamingWebSocketServlet;
  private final ArtifactCacheHandler artifactCacheHandler;

  /**
   * @param port If 0, then an <a href="http://en.wikipedia.org/wiki/Ephemeral_port">ephemeral
   *     port</a> will be assigned. Use {@link #getPort()} to find out which port is being used.
   */
  public WebServer(int port, ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
    this.port = Optional.empty();
    this.server = new Server(port);
    this.streamingWebSocketServlet = new StreamingWebSocketServlet();
    this.artifactCacheHandler = new ArtifactCacheHandler(projectFilesystem);
  }

  public Optional<Integer> getPort() {
    if (!port.isPresent()) {
      for (Connector connector : server.getConnectors()) {
        if (connector instanceof ServerConnector) {
          port = Optional.of(((ServerConnector) connector).getLocalPort());
          break;
        }
      }
    }

    return port;
  }

  public WebServerBuckEventListener createListener() {
    return new WebServerBuckEventListener(this);
  }

  public StreamingWebSocketServlet getStreamingWebSocketServlet() {
    return streamingWebSocketServlet;
  }

  /** @return Number of clients streaming from webserver */
  public int getNumActiveConnections() {
    return streamingWebSocketServlet.getNumActiveConnections();
  }

  /**
   * Update state and start the server if necessary.
   *
   * @param artifactCache cache to serve.
   * @throws WebServerException
   */
  public synchronized void updateAndStartIfNeeded(Optional<ArtifactCache> artifactCache)
      throws WebServerException {
    artifactCacheHandler.setArtifactCache(artifactCache);

    if (server.isStarted()) {
      return;
    }

    // Package up all of the handlers into a ContextHandlerCollection to serve as the handler for
    // the server.
    ImmutableList<? extends Handler> handlers = createHandlers();
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
  ImmutableList<ContextHandler> createHandlers() {
    Map<String, Handler> contextPathToHandler = new HashMap<>();

    contextPathToHandler.put(INDEX_CONTEXT_PATH, new TemplateHandler(new IndexHandlerDelegate()));

    StaticResourcesHandler staticResourcesHandler = new StaticResourcesHandler();
    contextPathToHandler.put(STATIC_CONTEXT_PATH, staticResourcesHandler);

    // Handlers for traces.
    BuildTraces buildTraces = new BuildTraces(projectFilesystem);
    contextPathToHandler.put(
        TRACE_CONTEXT_PATH, new TemplateHandler(new TraceHandlerDelegate(buildTraces)));
    contextPathToHandler.put(
        TRACES_CONTEXT_PATH, new TemplateHandler(new TracesHandlerDelegate(buildTraces)));
    contextPathToHandler.put(TRACE_DATA_CONTEXT_PATH, new TraceDataHandler(buildTraces));
    contextPathToHandler.put(ARTIFACTS_CONTEXT_PATH, artifactCacheHandler);

    ImmutableList.Builder<ContextHandler> handlers = ImmutableList.builder();
    for (Map.Entry<String, Handler> entry : contextPathToHandler.entrySet()) {
      String contextPath = entry.getKey();
      Handler handler = entry.getValue();
      ContextHandler contextHandler = new ContextHandler(contextPath);
      contextHandler.setHandler(handler);
      handlers.add(contextHandler);
    }

    // Create a handler that acts as a WebSocket server.
    ServletContextHandler servletContextHandler =
        new ServletContextHandler(
            /* parent */ server,
            /* contextPath */ "/ws",
            /* sessions */ true,
            /* security */ false);
    servletContextHandler.addServlet(new ServletHolder(streamingWebSocketServlet), "/build");
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

  public static class WebServerException extends Exception {

    public WebServerException(String message, Exception clause) {
      super(message, clause);
    }
  }
}
