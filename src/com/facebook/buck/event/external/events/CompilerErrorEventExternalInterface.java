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

package com.facebook.buck.event.external.events;

import com.google.common.collect.ImmutableSet;

/**
 * Describes a compiler error thrown by buck. This type is intended to be used by external
 * applications (like the Intellij Buck plugin) to deserialize events coming from the webserver.
 */
public interface CompilerErrorEventExternalInterface extends BuckEventExternalInterface {
  // Sent when a compiler error has been found
  String COMPILER_ERROR_EVENT = "CompilerErrorEvent";
  /** @return the stacktrace of the error that occurred. */
  String getError();
  /** @return the target on which the error occurred. */
  String getTarget();
  /** @return suggestions on how to fix the error. */
  ImmutableSet<String> getSuggestions();
}
