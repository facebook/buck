/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.args;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Expands a delegated arg to a file and then appends @that/file/path.macro to the command line. */
public class WriteToFileArg implements Arg {
  @AddToRuleKey(stringify = true)
  private final BuildTarget target;

  @AddToRuleKey private final String prefix;
  @AddToRuleKey private final Arg delegate;

  public WriteToFileArg(BuildTarget target, String prefix, Arg delegate) {
    this.target = target;
    this.prefix = prefix;
    this.delegate = delegate;
  }

  public static Path getMacroPath(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getScratchPath(projectFilesystem, buildTarget, "%s__macros");
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    try {
      ProjectFilesystem filesystem =
          pathResolver.getFilesystem(DefaultBuildTargetSourcePath.of(target));
      Path tempFile = createTempFile(filesystem, target);
      filesystem.writeContentsToPath(getContent(pathResolver), tempFile);
      consumer.accept("@" + filesystem.resolve(tempFile));
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(e, "When creating file to hold macro results.");
    }
  }

  /** @return The absolute path to the temp file. */
  private Path createTempFile(ProjectFilesystem filesystem, BuildTarget target) throws IOException {
    Path directory = getMacroPath(filesystem, target);
    filesystem.mkdirs(directory);
    return filesystem.createTempFile(directory, prefix, ".macro");
  }

  /**
   * Get the expanded content to write to the file. For some macros, the expanded value needs to be
   * different when written to a file.
   */
  protected String getContent(SourcePathResolver pathResolver) {
    StringBuilder builder = new StringBuilder();
    delegate.appendToCommandLine(builder::append, pathResolver);
    return builder.toString();
  }
}
