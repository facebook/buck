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

package com.facebook.buck.downwardapi.processexecutor.handlers;

import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.google.protobuf.AbstractMessage;

/** Event handler interface for Downward API */
public interface EventHandler<T extends AbstractMessage> {

  /** Handles received {@code event}. */
  void handleEvent(DownwardApiExecutionContext context, T event);
}
