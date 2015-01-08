/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.file;

import static java.nio.charset.StandardCharsets.UTF_16;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBusFactory;
import com.google.common.base.Optional;
import com.google.common.io.Files;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.MovedContextHandler;
import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.log.Log;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HttpDownloaderIntegrationTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static Server server;
  private static int port;
  private Downloader downloader;
  private Path outputDir;

  @BeforeClass
  public static void startHttpd() throws Exception {
    // Configured using a singleton. *furrfu*
    Log.setLog(new JavaUtilLog());
    server = new Server();

    ServerConnector connector = new ServerConnector(server);
    connector.addConnectionFactory(new HttpConnectionFactory());
    // Choose a port randomly upon listening for socket connections.
    connector.setPort(0);
    server.addConnector(connector);

    HandlerList handlers = new HandlerList();
    handlers.addHandler(new MovedContextHandler(null, "/redirect", "/out"));
    handlers.addHandler(new StaticContent("cheese"));
    handlers.addHandler(new DefaultHandler());

    server.setHandler(handlers);
    server.start();

    // Since we called setPort(0), an unused port number is chosen
    // upon calling start().  This returns the actual port number to
    // which the socket is locally bound.
    port = connector.getLocalPort();
  }

  @AfterClass
  public static void shutdownHttpd() throws Exception {
    server.stop();
    server.join();
  }

  @Before
  public void createDownloader() throws IOException {
    downloader = new HttpDownloader(Optional.<Proxy>absent(), Optional.<String>absent());
    outputDir = tmp.newFolder().toPath();
  }

  @Test
  public void canDownloadFromAUrlDirectly() throws IOException, URISyntaxException {
    URI uri = new URI(String.format("http://localhost:%d/example", port));

    Path output = outputDir.resolve("cheese");
    downloader.fetch(BuckEventBusFactory.newInstance(), uri, output);

    assertEquals("cheese", Files.toString(output.toFile(), UTF_16));
  }

  @Test
  public void canDownloadFromAUrlWithARedirect() throws IOException, URISyntaxException {
    URI uri = new URI(String.format("http://localhost:%d/redirect", port));

    Path output = outputDir.resolve("cheese");
    downloader.fetch(BuckEventBusFactory.newInstance(), uri, output);

    assertEquals("cheese", Files.toString(output.toFile(), UTF_16));
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
