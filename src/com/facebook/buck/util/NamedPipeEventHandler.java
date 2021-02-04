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

package com.facebook.buck.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

/** Named pipe event handler - process events that came from named pipe with DownwardAPI */
public interface NamedPipeEventHandler {

  /** Runs handler on the given {@code threadPool} */
  void runOn(ThreadPoolExecutor threadPool);

  /** Terminate and wait for {@link NamedPipeEventHandler} to finish processing events. */
  void terminateAndWait()
      throws CancellationException, InterruptedException, ExecutionException, TimeoutException;

  /**
   * Updates thread id that would be set into events handled by the current event handler.
   *
   * <p>Named pipe event handler is created together with launched process. If launched process is
   * reused by multiple building threads that means that thread id set into downward API execution
   * context is no longer a valid one. Thread id is set into events processed by named pipe event
   * handler and that is why it should be updated in case of thread switching.
   */
  void updateThreadId();
}
