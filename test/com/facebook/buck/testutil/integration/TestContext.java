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

import com.facebook.buck.cli.EnvironmentFilter;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGConstants;
import com.martiansoftware.nailgun.NGContext;
import com.martiansoftware.nailgun.NGInputStream;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;

/**
 * NGContext test double.
 */
public class TestContext extends NGContext implements Closeable {

  private Properties properties;
  private HashSet<NGClientListener> listeners;
  private CapturingPrintStream serverLog;
  private boolean addListeners;

  /**
   * Simulates client that never disconnects, with normal system environment.
   */
  public TestContext() {
    this(
        EnvironmentFilter.filteredEnvironment(
          ImmutableMap.copyOf(System.getenv()),
          Platform.detect()),
        createDisconnectionStream(0),
        0);
    addListeners = false; // Only track disconnections when input stream supplied.
  }

  /**
   * Simulates client that never disconnects, with given environment.
   */
  public TestContext(ImmutableMap<String, String> environment) {
    this(environment,
        createDisconnectionStream(0),
        0);
    addListeners = false; // Only track disconnections when input stream supplied.
  }

  /**
   * Simulates client connected to given stream, with given timeout and environment.
   * If stream blocks for longer than timeout, or throws an exception, a client disconnection
   * is triggered as normal.
   */
  public TestContext(ImmutableMap<String, String> environment,
      InputStream clientStream,
      long timeoutMillis) {
    serverLog = new CapturingPrintStream();
    in = new NGInputStream(
        new DataInputStream(Preconditions.checkNotNull(clientStream)),
        new DataOutputStream(new ByteArrayOutputStream(0)),
        serverLog, (int) timeoutMillis);
    out = new CapturingPrintStream();
    err = new CapturingPrintStream();
    setExitStream(new CapturingPrintStream());
    properties = new Properties();
    for (String key : environment.keySet()) {
      properties.setProperty(key, environment.get(key));
    }
    listeners = new HashSet<>();
    addListeners = true;
  }

  @Override
  public Properties getEnv() {
    return properties;
  }

  @Override
  public void addClientListener(NGClientListener listener) {
    if (addListeners) {
      listeners.add(listener);
      super.addClientListener(listener);
    }
  }

  @Override
  public void removeClientListener(NGClientListener listener) {
    if (addListeners) {
      listeners.remove(listener);
      super.removeClientListener(listener);
    }
  }

  public ImmutableSet<NGClientListener> getListeners() {
    return ImmutableSet.copyOf(listeners);
  }

  /**
   * Generates heartbeat chunks at a given interval.
   */
  public static InputStream createHeartBeatStream(final long heartbeatIntervalMillis) {
    return new InputStream() {
      private final int bytesPerHeartbeat = 5;
      private final long byteInterval = heartbeatIntervalMillis / bytesPerHeartbeat;

      @Override
      public int read() throws IOException {
        try {
          Thread.sleep(byteInterval);
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
        return NGConstants.CHUNKTYPE_HEARTBEAT;
      }
    };
  }


  /**
   * @param disconnectMillis duration to wait before generating IOException.
   * @return an InputStream which will wait and then simulate a client disconnection.
   */
  public static InputStream createDisconnectionStream(final long disconnectMillis) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        try {
          Thread.sleep(disconnectMillis);
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
        throw new IOException("Fake client disconnection.");
      }
    };
  }

  @Override
  public void close() throws IOException {
    in.close();
  }

  public String getServerLog() {
    return serverLog.getContentsAsString(Charsets.US_ASCII);
  }
}
