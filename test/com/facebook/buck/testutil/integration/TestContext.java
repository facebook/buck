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

package com.facebook.buck.testutil.integration;

import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.collect.ImmutableMap;
import com.martiansoftware.nailgun.NGClientDisconnectReason;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** NGContext test double. */
public class TestContext extends NGContext implements Closeable {

  private Properties properties;
  private Set<NGClientListener> listeners;
  private Optional<ScheduledExecutorService> clientDisconnectService = Optional.empty();

  /** Simulates client that never disconnects, with normal system environment. */
  public TestContext() {
    this(ImmutableMap.copyOf(System.getenv()), createNoOpStream(), 0);
  }

  /** Simulates client that never disconnects, with given environment. */
  public TestContext(ImmutableMap<String, String> environment) {
    this(environment, createNoOpStream(), 0);
  }

  /** Simulates client that disconnects after timeout, with given environment. */
  public TestContext(ImmutableMap<String, String> environment, long timeoutMillis) {
    this(environment, createNoOpStream(), timeoutMillis);
  }

  /** Simulates client connected to given stream, with given environment and disconnect timeout */
  public TestContext(
      ImmutableMap<String, String> environment, InputStream clientStream, long timeoutMillis) {

    in = new DataInputStream(clientStream);
    out = new CapturingPrintStream();
    err = new CapturingPrintStream();
    properties = new Properties();
    for (String key : environment.keySet()) {
      properties.setProperty(key, environment.get(key));
    }
    listeners = new HashSet<>();
    if (timeoutMillis > 0) {
      clientDisconnectService = Optional.of(Executors.newSingleThreadScheduledExecutor());
      clientDisconnectService
          .get()
          .schedule(this::notifyListeners, timeoutMillis, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public Properties getEnv() {
    return properties;
  }

  @Override
  public void addClientListener(NGClientListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeClientListener(NGClientListener listener) {
    listeners.remove(listener);
  }

  @Override
  public void removeAllClientListeners() {
    listeners.clear();
  }

  private void notifyListeners() {
    listeners.forEach(listener -> listener.clientDisconnected(NGClientDisconnectReason.HEARTBEAT));
  }

  @Override
  public void exit(int exitCode) {}

  /** @return an InputStream which does nothing */
  public static InputStream createNoOpStream() {
    return new InputStream() {
      @Override
      public int read() {
        return -1;
      }
    };
  }

  @Override
  public void close() throws IOException {
    in.close();
    out.close();
    err.close();

    clientDisconnectService.ifPresent(service -> service.shutdownNow());
  }
}
