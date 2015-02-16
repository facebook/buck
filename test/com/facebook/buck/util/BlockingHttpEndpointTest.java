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

package com.facebook.buck.util;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class BlockingHttpEndpointTest {

  private static final long timeoutMillis = 1000L;

  private BlockingHttpEndpoint createBlockingHttpEndpoint() throws MalformedURLException {
    return new BlockingHttpEndpoint("http://example.com", 1, (int) timeoutMillis);
  }

  @Test
  public void whenRequestServiceTerminatesThenExceptionNotThrownByClose()
      throws IOException {
    long start = System.nanoTime();
    BlockingHttpEndpoint endpoint = createBlockingHttpEndpoint();
    endpoint.send(new TestHttpURLConnection(0), "Foo");
    endpoint.close();
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat("Shutdown should not take a long time.",
        durationMillis,
        lessThanOrEqualTo(timeoutMillis));
  }

  @Test
  public void whenRequestServiceTimesOutCloseStillWorks()
      throws IOException {
    long start = System.nanoTime();
    BlockingHttpEndpoint endpoint = createBlockingHttpEndpoint();
    endpoint.send(new TestHttpURLConnection(timeoutMillis), "Foo");
    endpoint.close();
    // We'd like to test the Logger output here, but there's not a clean way to do that.
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat("Shutdown should not take a long time.",
        durationMillis,
        lessThanOrEqualTo(timeoutMillis * 3));
  }

  private static class TestHttpURLConnection extends HttpURLConnection {
    private final long delayPeriodMillis;

    @Override
    public InputStream getInputStream() throws IOException {
      try {
        Thread.sleep(delayPeriodMillis);
      } catch (InterruptedException e) {
        e.printStackTrace();
        Thread.currentThread().interrupt();
      }
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      try {
        Thread.sleep(delayPeriodMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new ByteArrayOutputStream(0);
    }

    public TestHttpURLConnection(long delayPeriodMillis) throws MalformedURLException {
      super(new URL("http://example.com"));
      this.delayPeriodMillis = delayPeriodMillis;
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() throws IOException {
    }
  }
}
