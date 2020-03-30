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

package com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers;

import com.google.common.collect.ImmutableSet;
import com.intellij.util.messages.Topic;

public interface CompilerErrorConsumer {
  Topic<CompilerErrorConsumer> COMPILER_ERROR_CONSUMER =
      Topic.create("buck.compiler.error", CompilerErrorConsumer.class);

  void consumeCompilerError(
      String target, long timestamp, String error, ImmutableSet<String> suggestions);
}
