/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.testutil.integration;

import static java.nio.charset.StandardCharsets.UTF_16;
import static org.junit.Assert.assertFalse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.log.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
* Lightweight wrapper around an httpd to make testing using an httpd nicer.
*/
public class HttpdForTests implements AutoCloseable {

  private final HandlerList handlerList;
  private Server server;
  private boolean isRunning;

  public HttpdForTests() {
    // Configure the logging for jetty. Which uses a singleton. Ho hum.
    Log.setLog(new JavaUtilLog());
    server = new Server();

    ServerConnector connector = new ServerConnector(server);
    connector.addConnectionFactory(new HttpConnectionFactory());
    // Choose a port randomly upon listening for socket connections.
    connector.setPort(0);
    server.addConnector(connector);

    handlerList = new HandlerList();
  }

  public void addHandler(Handler handler) {
    assertFalse(isRunning);

    handlerList.addHandler(handler);
  }

  public void addStaticContent(String contentToReturn) {
    addHandler(new StaticContent(contentToReturn));
  }

  public void start() throws Exception {
    assertFalse(isRunning);

    handlerList.addHandler(new DefaultHandler());
    server.setHandler(handlerList);

    server.start();
  }

  @Override
  public void close() throws Exception {
    server.stop();
    server.join();
    isRunning = false;
  }

  public URI getUri(String path) throws URISyntaxException {
    URI baseUri;
    try {
      baseUri = server.getURI();
    } catch (Exception e) {
      // We'd rather catch UnknownHostException, but that's a checked exception that is claimed
      // never to be thrown.
      baseUri = null;
    }
    if (baseUri == null) {
      for (Connector connector : server.getConnectors()) {
        if (connector instanceof NetworkConnector) {
          return new URI("http://localhost:" + ((NetworkConnector) connector).getLocalPort());
        }
      }
      throw new RuntimeException("Unable to determine URL of localhost.");
    }
    return new URI(
        baseUri.getScheme(), /* user info */
        null,
        baseUri.getHost(),
        baseUri.getPort(),
        path,
        null,
        null);
  }

  private static class StaticContent extends AbstractHandler {

    private final String content;

    public StaticContent(String content) {
      this.content = content;
    }

    @Override
    public void handle(
        String target,
        Request request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse) throws IOException, ServletException {
      // Use an unusual charset.
      httpServletResponse.getOutputStream().write(content.getBytes(UTF_16));
      request.setHandled(true);
    }
  }
}
