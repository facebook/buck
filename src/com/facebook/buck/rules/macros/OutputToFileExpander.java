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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;

public class OutputToFileExpander implements MacroExpander {
  private final MacroExpander delegate;

  public OutputToFileExpander(MacroExpander delegate) {
    this.delegate = delegate;
  }

  @Override
  public String expand(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input) throws MacroException {

    try {
      Path tempFile = createTempFile(filesystem, target, input);
      String expanded;
      if (delegate instanceof MacroExpanderWithCustomFileOutput) {
        expanded = ((MacroExpanderWithCustomFileOutput) delegate).expandForFile(
            target,
            resolver,
            filesystem,
            input);
      } else {
        expanded = delegate.expand(target, resolver, filesystem, input);
      }

      filesystem.writeContentsToPath(expanded, tempFile);
      return "@" + filesystem.getAbsolutifier().apply(tempFile);
    } catch (IOException e) {
      throw new MacroException("Unable to create file to hold expanded results", e);
    }
  }

  @Override
  public ImmutableList<BuildRule> extractAdditionalBuildTimeDeps(
      BuildTarget target,
      BuildRuleResolver resolver,
      String input)
      throws MacroException {
    return delegate.extractAdditionalBuildTimeDeps(target, resolver, input);
  }

  @Override
  public ImmutableList<BuildTarget> extractParseTimeDeps(BuildTarget target, String input)
      throws MacroException {
    return delegate.extractParseTimeDeps(target, input);
  }

  /**
   * @return The absolute path to the temp file.
   */
  private Path createTempFile(ProjectFilesystem filesystem, BuildTarget target, String input)
      throws IOException {
    Path directory = BuildTargets.getScratchPath(target, "%s/tmp");
    filesystem.mkdirs(directory);

    // "prefix" should give a stable name, so that the same delegate with the same input can output
    // the same file. We won't optimise for this case, since it's actually unlikely to happen within
    // a single run, but using a random name would cause 'buck-out' to expand in an uncontrolled
    // manner.
    String prefix = Hashing.sha1().newHasher()
        .putString(delegate.getClass().getName(), UTF_8)
        .putString(input, UTF_8)
        .hash()
        .toString();

    // ProjectFilesystem.createTempFile expects an absolute path, so make sure we're using one
    Path absolute = filesystem.resolve(directory);
    Path temp = filesystem.createTempFile(absolute, prefix, ".macro");

    // And now return the actual path to the file in a form relative to the file system root.
    return directory.resolve(temp.getFileName());
  }
}
