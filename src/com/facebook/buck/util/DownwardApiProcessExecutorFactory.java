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
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.util.timing.Clock;

/** Factory interface that creates {@link ProcessExecutor} which supports Downward API. */
@FunctionalInterface
public interface DownwardApiProcessExecutorFactory {

  /** Creates {@link ProcessExecutor} which supports Downward API. */
  ProcessExecutor create(
      ProcessExecutor delegate,
      NamedPipeEventHandlerFactory namedPipeEventHandlerFactory,
      ConsoleParams consoleParams,
      IsolatedEventBus buckEventBus,
      ActionId actionId,
      Clock clock);
}
