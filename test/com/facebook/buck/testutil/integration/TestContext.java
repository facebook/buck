/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testutil.integration;

import com.facebook.nailgun.NGClientListener;
import com.facebook.nailgun.NGContext;
import com.google.common.collect.ImmutableMap;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** NGContext test double. */
public class TestContext extends NGContext implements Closeable {

  private final Properties properties;
  private final Set<NGClientListener> listeners;

  /** Simulates client that never disconnects, with normal system environment. */
  public TestContext() {
    this(ImmutableMap.of());
  }

  /** Simulates client that never disconnects, with given environment. */
  public TestContext(ImmutableMap<String, String> environment) {

    ImmutableMap<String, String> sanitizedEnv =
        EnvironmentSanitizer.getSanitizedEnvForTests(environment);

    in = createNoOpInStream();
    out = createNoOpOutStream();
    properties = new Properties();
    for (Map.Entry<String, String> entry : sanitizedEnv.entrySet()) {
      properties.setProperty(entry.getKey(), entry.getValue());
    }
    listeners = new HashSet<>();
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

  @Override
  public void exit(int exitCode) {}

  /** @return an InputStream which does nothing */
  public static InputStream createNoOpInStream() {
    return new InputStream() {
      @Override
      public int read() {
        return -1;
      }
    };
  }

  /** @return an InputStream which does nothing */
  public static PrintStream createNoOpOutStream() {
    return new PrintStream(
        new OutputStream() {
          @Override
          public void write(int b) {}
        });
  }

  @Override
  public void close() throws IOException {
    in.close();
    out.close();
  }
}
