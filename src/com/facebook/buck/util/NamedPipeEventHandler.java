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

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
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
   * Register action id with this handler.
   *
   * <p>Named pipe event handler is created together with launched process. Launched process could
   * be reused by multiple building threads. Handler has to set a valid value of thread id into
   * every event processed by downward API execution. By registering action id with this handler,
   * downward API execution would know about invoking thread id and would set it to every event that
   * has this action id.
   */
  void registerActionId(ActionId actionId);
}
