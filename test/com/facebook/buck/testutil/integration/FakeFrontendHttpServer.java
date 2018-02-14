/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.io.ByteStreams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Fake Stampede.Frontend server that provides enough of an HTTP API surface to allow us to run
 * integration tests in the local machine without have to connect to any real remote
 * Stampede.Frontend servers.
 */
public abstract class FakeFrontendHttpServer implements Closeable {

  private static final int MAX_CONNECTIONS_WAITING_IN_QUEUE = 42;

  private final int port;
  private final Thread serverThread;
  private final HttpServer server;

  public FakeFrontendHttpServer() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress(0), MAX_CONNECTIONS_WAITING_IN_QUEUE);
    this.port = this.server.getAddress().getPort();
    this.server.createContext("/status.php", httpExchange -> handleStatusRequest(httpExchange));
    this.server.createContext("/thrift", httpExchange -> handleThriftRequest(httpExchange));
    this.serverThread =
        new Thread(
            () -> {
              server.start();
            });
    this.serverThread.start();
  }

  public abstract FrontendResponse handleRequest(FrontendRequest request);

  private void handleStatusRequest(HttpExchange httpExchange) throws IOException {
    byte[] iAmAlive = "I am alive and happy!!!".getBytes();
    httpExchange.sendResponseHeaders(200, iAmAlive.length);
    try (OutputStream os = httpExchange.getResponseBody()) {
      os.write(iAmAlive);
    }
  }

  private void handleThriftRequest(HttpExchange httpExchange) throws IOException {
    byte[] requestBytes = ByteStreams.toByteArray(httpExchange.getRequestBody());
    FrontendRequest request = new FrontendRequest();
    ThriftUtil.deserialize(ThriftProtocol.BINARY, requestBytes, request);

    FrontendResponse response = handleRequest(request);

    byte[] responseBuffer = ThriftUtil.serialize(ThriftProtocol.BINARY, response);
    httpExchange.sendResponseHeaders(200, responseBuffer.length);
    try (OutputStream os = httpExchange.getResponseBody()) {
      os.write(responseBuffer);
    }
  }

  @Override
  public void close() throws IOException {
    this.server.stop(0);
    try {
      this.serverThread.join();
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  public String getPingEndpointConfigArg() {
    return "--config=stampede.slb_ping_endpoint=/status.php";
  }

  public String getStampedeConfigArg() {
    return String.format("--config=stampede.slb_server_pool=http://localhost:%s", port);
  }
}
