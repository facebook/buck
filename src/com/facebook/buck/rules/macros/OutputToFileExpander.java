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

package com.facebook.buck.rules.macros;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class OutputToFileExpander implements MacroExpander {
  private final MacroExpander delegate;

  public OutputToFileExpander(MacroExpander delegate) {
    this.delegate = delegate;
  }

  @Override
  public String expand(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input)
      throws MacroException {

    try {
      String expanded;
      if (delegate instanceof MacroExpanderWithCustomFileOutput) {
        expanded =
            ((MacroExpanderWithCustomFileOutput) delegate)
                .expandForFile(target, cellNames, resolver, input);
      } else {
        expanded = delegate.expand(target, cellNames, resolver, input);
      }

      Optional<BuildRule> rule = resolver.getRuleOptional(target);
      if (!rule.isPresent()) {
        throw new MacroException(String.format("no rule %s", target));
      }
      ProjectFilesystem filesystem = rule.get().getProjectFilesystem();
      Path tempFile = createTempFile(filesystem, target, input.get(0));
      filesystem.writeContentsToPath(expanded, tempFile);
      return "@" + filesystem.resolve(tempFile);
    } catch (IOException e) {
      throw new MacroException("Unable to create file to hold expanded results", e);
    }
  }

  @Override
  public ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input)
      throws MacroException {
    return delegate.extractBuildTimeDeps(target, cellNames, resolver, input);
  }

  @Override
  public void extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
      throws MacroException {
    delegate.extractParseTimeDeps(
        target, cellNames, input, buildDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  @Override
  @Nullable
  public Object extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input)
      throws MacroException {
    return delegate.extractRuleKeyAppendables(target, cellNames, resolver, input);
  }

  /** @return The absolute path to the temp file. */
  private Path createTempFile(ProjectFilesystem filesystem, BuildTarget target, String input)
      throws IOException {
    Path directory = BuildTargets.getScratchPath(filesystem, target, "%s/tmp");
    filesystem.mkdirs(directory);

    // "prefix" should give a stable name, so that the same delegate with the same input can output
    // the same file. We won't optimise for this case, since it's actually unlikely to happen within
    // a single run, but using a random name would cause 'buck-out' to expand in an uncontrolled
    // manner.
    String prefix =
        Hashing.sha1()
            .newHasher()
            .putString(delegate.getClass().getName(), UTF_8)
            .putString(input, UTF_8)
            .hash()
            .toString();

    return filesystem.createTempFile(directory, prefix, ".macro");
  }
}
