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

package com.facebook.buck.downwardapi.processexecutor;

import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeServer;
import com.facebook.buck.util.types.Unit;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

class TestNamedPipeWithException implements NamedPipeReader, NamedPipeServer {

  private final NamedPipe delegate;

  TestNamedPipeWithException(NamedPipe delegate) {
    this.delegate = delegate;
  }

  @Override
  public InputStream getInputStream() {
    throw new RuntimeException("hello");
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public void prepareToClose(Future<Unit> readyToClose)
      throws IOException, ExecutionException, TimeoutException, InterruptedException {
    ((NamedPipeServer) delegate).prepareToClose(readyToClose);
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
