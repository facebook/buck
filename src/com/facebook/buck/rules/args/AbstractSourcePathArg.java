/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.immutables.value.Value;

/** An {@link Arg} which wraps a {@link SourcePath}. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractSourcePathArg implements Arg, HasSourcePath {
  @Override
  @AddToRuleKey
  public abstract SourcePath getPath();

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    appendToCommandLine(consumer, pathResolver, false);
  }

  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolver pathResolver, boolean useUnixPathSeparator) {
    String line = pathResolver.getAbsolutePath(getPath()).toString();
    if (useUnixPathSeparator) {
      line = MorePaths.pathWithUnixSeparators(line);
    }
    consumer.accept(line);
  }

  public void appendToCommandLineRel(
      Consumer<String> consumer,
      Path cellPath,
      SourcePathResolver pathResolver,
      boolean useUnixPathSeparator) {
    SourcePath path = getPath();
    if (path instanceof BuildTargetSourcePath
        && cellPath.equals(((BuildTargetSourcePath) path).getTarget().getCellPath())) {
      String line = pathResolver.getRelativePath(path).toString();
      if (useUnixPathSeparator) {
        line = MorePaths.pathWithUnixSeparators(line);
      }
      consumer.accept(line);
    } else {
      appendToCommandLine(consumer, pathResolver, useUnixPathSeparator);
    }
  }

  public static ImmutableList<Arg> from(Iterable<SourcePath> paths) {
    ImmutableList.Builder<Arg> converted = ImmutableList.builder();
    for (SourcePath path : paths) {
      converted.add(SourcePathArg.of(path));
    }
    return converted.build();
  }

  public static ImmutableList<Arg> from(SourcePath... paths) {
    return from(ImmutableList.copyOf(paths));
  }
}
