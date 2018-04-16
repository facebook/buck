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
import static org.junit.Assert.assertTrue;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;

/** Lightweight wrapper around an httpd to make testing using an httpd nicer. */
public class HttpdForTests implements AutoCloseable {

  private final HandlerList handlerList;
  private final Server server;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final String localhost;

  public HttpdForTests() throws SocketException {
    // Configure the logging for jetty. Which uses a singleton. Ho hum.
    Log.setLog(new JavaUtilLog());
    server = new Server();

    ServerConnector connector = new ServerConnector(server);
    connector.addConnectionFactory(new HttpConnectionFactory());
    // Choose a port randomly upon listening for socket connections.
    connector.setPort(0);
    server.addConnector(connector);

    handlerList = new HandlerList();
    localhost = getLocalhostAddress().getHostAddress();
  }

  public void addHandler(Handler handler) {
    assertFalse(isRunning.get());

    handlerList.addHandler(handler);
  }

  public void addStaticContent(String contentToReturn) {
    addHandler(new StaticContent(contentToReturn));
  }

  public void start() throws Exception {
    assertTrue(isRunning.compareAndSet(false, true));

    handlerList.addHandler(new DefaultHandler());
    server.setHandler(handlerList);

    server.start();
  }

  @Override
  public void close() throws Exception {
    server.stop();
    server.join();
    isRunning.set(false);
  }

  public URI getRootUri() {
    try {
      return getUri("/");
    } catch (URISyntaxException e) {
      // Should never happen
      throw new RuntimeException(e);
    }
  }

  public URI getUri(String path) throws URISyntaxException {
    assertTrue(
        "Server must be running before retrieving a URI, otherwise the resulting URI may "
            + "not have an appropriate port",
        isRunning.get());
    URI baseUri;
    try {
      baseUri = server.getURI();
    } catch (Exception e) {
      // We'd rather catch UnknownHostException, but that's a checked exception that is claimed
      // never to be thrown.
      baseUri = new URI("http://localhost/");
    }

    Preconditions.checkNotNull(baseUri, "Unable to determine baseUri");

    // It turns out that if we got baseUri from Jetty it may have just Made Stuff Up. To avoid this,
    // we only use the scheme and port that Jetty returned.
    return new URI(
        baseUri.getScheme(), /* user info */ null, localhost, baseUri.getPort(), path, null, null);
  }

  /**
   * @return an address that's either on a loopback or local network interface that refers to
   *     localhost.
   */
  private InetAddress getLocalhostAddress() throws SocketException {
    // It turns out that:
    //   InetAddress.getLocalHost().getHostAddress()
    // will occasionally just make up return values. I have no idea why. To work around this, figure
    // out this stuff automagically.

    // First, we collect every possible InetAddress we might be able to return
    Set<InetAddress> candidateLoopbacks = new HashSet<>();
    Set<InetAddress> candidateLocal = new HashSet<>();

    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    Preconditions.checkNotNull(interfaces, "Apprently this machine has no network interfaces.");

    while (interfaces.hasMoreElements()) {
      NetworkInterface iface = interfaces.nextElement();
      if (!iface.isUp()) {
        continue;
      }
      if (iface.isLoopback()) {
        candidateLoopbacks.addAll(getInetAddresses(iface));
      } else {
        candidateLocal.addAll(getInetAddresses(iface));
      }
    }

    // We need at least one inet address in order to continue.
    Preconditions.checkState(!candidateLoopbacks.isEmpty() || !candidateLocal.isEmpty());

    // Prefer a loopback device to going out over the NIC.
    if (!candidateLoopbacks.isEmpty()) {
      return candidateLoopbacks.iterator().next();
    }
    return candidateLocal.iterator().next();
  }

  private Set<InetAddress> getInetAddresses(NetworkInterface iface) {
    Set<InetAddress> toReturn = new HashSet<>();
    Enumeration<InetAddress> addresses = iface.getInetAddresses();

    while (addresses.hasMoreElements()) {
      toReturn.add(addresses.nextElement());
    }

    return toReturn;
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
        HttpServletResponse httpServletResponse)
        throws IOException {
      // Use an unusual charset.
      httpServletResponse.getOutputStream().write(content.getBytes(UTF_16));
      request.setHandled(true);
    }
  }

  public static class DummyPutRequestsHandler extends AbstractHandler {

    private final List<String> putRequestsPaths = new ArrayList<>();

    @Override
    public void handle(
        String target,
        Request request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse) {
      if (!HttpMethod.PUT.is(request.getMethod())) {
        return;
      }
      putRequestsPaths.add(request.getHttpURI().getPath());
      request.setHandled(true);
    }

    public List<String> getPutRequestsPaths() {
      return putRequestsPaths;
    }
  }

  public static class FileDispenserRequestHandler extends ContextHandler {

    public FileDispenserRequestHandler(Path rootDir) {
      this(rootDir, "/");
    }

    public FileDispenserRequestHandler(Path rootDir, String urlBasePath) {
      super(urlBasePath);

      ResourceHandler resourceHandler = new ResourceHandler();
      resourceHandler.setDirectoriesListed(true);
      resourceHandler.setResourceBase(rootDir.toAbsolutePath().toString());

      setHandler(resourceHandler);
      setLogger(new StdErrLog());
    }
  }

  /**
   * A simple http handler that will return content at the given path (or 404 if not in the map),
   * and that records all paths that are requested.
   */
  public static class CapturingHttpHandler extends AbstractHandler {

    private ImmutableMap<String, byte[]> contentMap;
    private final List<String> requestedPaths = new ArrayList<>();

    /**
     * Creates an instance of {@link CapturingHttpHandler}
     *
     * @param contentMap A map of paths (including leading /) to content that should be returned as
     *     UTF-8 encoded strings
     */
    public CapturingHttpHandler(ImmutableMap<String, byte[]> contentMap) {
      this.contentMap = contentMap;
    }

    @Override
    public void handle(
        String s,
        Request request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse)
        throws IOException {
      synchronized (this) {
        requestedPaths.add(request.getHttpURI().getPath());
      }
      if (!contentMap.containsKey(request.getHttpURI().getPath())) {
        httpServletResponse.setStatus(404);
        request.setHandled(true);
        return;
      }
      httpServletResponse.setStatus(200);
      httpServletResponse.getOutputStream().write(contentMap.get(request.getHttpURI().getPath()));
      request.setHandled(true);
    }

    public ImmutableList<String> getRequestedPaths() {
      synchronized (this) {
        return ImmutableList.copyOf(requestedPaths);
      }
    }
  }
}
