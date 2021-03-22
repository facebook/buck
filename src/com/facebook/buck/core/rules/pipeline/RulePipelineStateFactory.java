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

package com.facebook.buck.core.rules.pipeline;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.protobuf.AbstractMessage;
import java.util.function.Function;

/** Factory that responsible for creating build rule pipelining state */
public interface RulePipelineStateFactory<
    State extends RulePipelineState, Message extends AbstractMessage> {

  /**
   * Creates an intermediate protobuf message that could be turn into build rule pipelining state
   */
  Message createPipelineStateMessage(
      BuildContext context, ProjectFilesystem filesystem, BuildTarget firstTarget);

  /**
   * Returns a function that could be applied to an intermediate state stored as a protobuf message
   * to create a build rule pipelining state.
   */
  Function<Message, State> getStateCreatorFunction();
}
