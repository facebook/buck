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

package com.facebook.buck.android;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** Run strip on a binary. */
public class StripStep extends IsolatedShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> stripCommandPrefix;
  private final ImmutableList<String> flags;
  private final AbsPath source;
  private final AbsPath destination;

  public StripStep(
      AbsPath workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> stripCommandPrefix,
      ImmutableList<String> flags,
      AbsPath source,
      AbsPath destination,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(workingDirectory, cellPath, withDownwardApi);
    this.environment = environment;
    this.stripCommandPrefix = stripCommandPrefix;
    this.flags = flags;
    this.source = source;
    this.destination = destination;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    // flavors embedded in the output path can make the path very very long
    String outputPath;
    if (context.getPlatform() == Platform.WINDOWS) {
      outputPath = MorePaths.getWindowsLongPathString(destination);
    } else {
      outputPath = destination.toString();
    }
    return ImmutableList.<String>builder()
        .addAll(stripCommandPrefix)
        .addAll(flags)
        .add(source.toString())
        .add("-o")
        .add(outputPath)
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "strip";
  }
}
