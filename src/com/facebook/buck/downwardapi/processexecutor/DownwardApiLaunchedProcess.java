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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.util.DelegateLaunchedProcess;
import com.facebook.buck.util.NamedPipeEventHandler;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Process launched inside the {@link DownwardApiProcessExecutor} */
class DownwardApiLaunchedProcess extends DelegateLaunchedProcess {

  private static final Logger LOG = Logger.get(DownwardApiLaunchedProcess.class);

  private final NamedPipe namedPipe;
  private final NamedPipeEventHandler namedPipeEventHandler;
  private boolean readerThreadTerminated = false;

  public DownwardApiLaunchedProcess(
      ProcessExecutor.LaunchedProcess delegate,
      NamedPipe namedPipe,
      NamedPipeEventHandler namedPipeEventHandler) {
    super(delegate);
    this.namedPipe = namedPipe;
    this.namedPipeEventHandler = namedPipeEventHandler;
  }

  @Override
  public void close() {
    super.close();
    cancelHandler();
    closeNamedPipe();
  }

  private void cancelHandler() {
    readerThreadTerminated = true;
    try {
      namedPipeEventHandler.terminateAndWait();
    } catch (CancellationException e) {
      // this is fine. it's just canceled
    } catch (InterruptedException e) {
      LOG.info(e, "Got interrupted while cancelling downward events processing");
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      LOG.warn(e.getCause(), "Exception while cancelling named pipe events processing.");
    } catch (TimeoutException e) {
      LOG.error(
          "Cannot shutdown downward api reader handler for named pipe: '%s'", namedPipe.getName());
      readerThreadTerminated = false;
    }
  }

  private void closeNamedPipe() {
    try {
      namedPipe.close();
    } catch (IOException e) {
      LOG.error(e, "Cannot close named pipe: %s", namedPipe.getName());
    }
  }

  @VisibleForTesting
  boolean isReaderThreadTerminated() {
    return readerThreadTerminated;
  }
}
