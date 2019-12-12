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

package com.facebook.buck.slb;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import okhttp3.Call;
import okhttp3.Response;

public class LoadBalancedHttpResponse extends OkHttpResponseWrapper {
  private final HttpLoadBalancer loadBalancer;
  private final URI server;
  private boolean hasConnectionResultBeenReported;

  private static final boolean FIX_HTTP_BOTTLENECK =
      "true".equals(System.getProperty("buck.fix_http_bottleneck"));

  public static LoadBalancedHttpResponse createLoadBalancedResponse(
      URI server, HttpLoadBalancer loadBalancer, Call call) throws IOException {
    try {
      return new LoadBalancedHttpResponse(server, loadBalancer, call.execute());
    } catch (IOException e) {
      if (FIX_HTTP_BOTTLENECK) {
        loadBalancer.reportRequestException(server);
      }
      throw e;
    }
  }

  @VisibleForTesting
  LoadBalancedHttpResponse(URI server, HttpLoadBalancer loadBalancer, Response response) {
    super(response);
    this.loadBalancer = loadBalancer;
    this.server = server;
    this.hasConnectionResultBeenReported = false;
  }

  @Override
  public long contentLength() throws IOException {
    try {
      return super.contentLength();
    } catch (IOException exception) {
      reportConnectionResultIfFirst(false);
      throw exception;
    }
  }

  @Override
  public InputStream getBody() {
    return new LoadBalancedInputStream(getResponse().body().byteStream());
  }

  @Override
  public void close() throws IOException {
    super.close();

    // We can only be sure a connection was successful after all data was read and the
    // connection successfully closed.
    reportConnectionResultIfFirst(true);
  }

  private void reportConnectionResultIfFirst(boolean successful) {
    if (hasConnectionResultBeenReported) {
      return;
    }

    hasConnectionResultBeenReported = true;
    if (successful) {
      loadBalancer.reportRequestSuccess(server);
    } else {
      loadBalancer.reportRequestException(server);
    }
  }

  private class LoadBalancedInputStream extends InputStream {
    private final InputStream rawStream;

    public LoadBalancedInputStream(InputStream stream) {
      this.rawStream = stream;
    }

    @Override
    public void close() throws IOException {
      rawStream.close();
    }

    @Override
    public int read() throws IOException {
      try {
        return rawStream.read();
      } catch (IOException e) {
        reportConnectionResultIfFirst(false);
        throw e;
      }
    }

    @Override
    public int read(byte[] b) throws IOException {
      try {
        return rawStream.read(b);
      } catch (IOException e) {
        reportConnectionResultIfFirst(false);
        throw e;
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      try {
        return rawStream.read(b, off, len);
      } catch (IOException e) {
        reportConnectionResultIfFirst(false);
        throw e;
      }
    }

    @Override
    public long skip(long n) throws IOException {
      try {
        return rawStream.skip(n);
      } catch (IOException e) {
        reportConnectionResultIfFirst(false);
        throw e;
      }
    }

    @Override
    public int available() throws IOException {
      try {
        return rawStream.available();
      } catch (IOException e) {
        reportConnectionResultIfFirst(false);
        throw e;
      }
    }

    @Override
    public synchronized void mark(int readlimit) {
      rawStream.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
      try {
        rawStream.reset();
      } catch (IOException e) {
        reportConnectionResultIfFirst(false);
        throw e;
      }
    }

    @Override
    public boolean markSupported() {
      return rawStream.markSupported();
    }
  }
}
