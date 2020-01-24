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

package com.facebook.buck.core.rules.actions.lib.args;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Streams;

/**
 * Build a {@link CommandLine} that is compatible with `exec()` style functions. Namely, this will
 * add "./" to the first argument if the first argument is an artifact so that its relative path can
 * be properly executed.
 */
public class ExecCompatibleCommandLineBuilder implements CommandLineBuilder {

  private final ArtifactFilesystem filesystem;

  public ExecCompatibleCommandLineBuilder(ArtifactFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  @Override
  public CommandLine build(CommandLineArgs commandLineArgs) {
    ImmutableSortedMap<String, String> env = commandLineArgs.getEnvironmentVariables();
    ImmutableList.Builder<String> builder =
        ImmutableList.builderWithExpectedSize(commandLineArgs.getEstimatedArgsCount());
    Streams.mapWithIndex(
            commandLineArgs.getArgsAndFormatStrings(),
            (o, i) -> CommandLineArgStringifier.asString(filesystem, i == 0, o))
        .forEach(builder::add);
    return ImmutableCommandLine.of(env, builder.build());
  }
}
