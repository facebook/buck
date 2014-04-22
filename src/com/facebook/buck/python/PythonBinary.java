/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class PythonBinary extends AbstractBuildable implements BinaryBuildRule {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  private final ImmutableSortedSet<BuildRule> deps;
  private final Path main;

  protected PythonBinary(ImmutableSortedSet<BuildRule> deps, Path main) {
    this.deps = Preconditions.checkNotNull(deps);
    this.main = Preconditions.checkNotNull(main);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    // We don't generate a python file for this, we use the binary to construct a python path which
    // we then execute. This is somewhat confusing.
    // TODO(simons): Add support for "wheel" or something similar.
    return null;  // I mean, seriously? OK.
  }

  @Override
  public List<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    String pythonPath = Joiner.on(':').join(
        Iterables.transform(
            getPythonPathEntries(),
            projectFilesystem.getAbsolutifier()));
    return ImmutableList.of(String.format("PYTHONPATH=%s", pythonPath), "python",
        projectFilesystem.getAbsolutifier().apply(main).toString());
  }

  @VisibleForTesting
  ImmutableSet<Path> getPythonPathEntries() {
    final ImmutableSet.Builder<Path> entries = ImmutableSet.builder();

    new AbstractDependencyVisitor(deps) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        Buildable buildable = rule.getBuildable();
        if (buildable instanceof PythonLibrary) {
          PythonLibrary pythonLibrary = (PythonLibrary) buildable;

          Path pythonPathEntry = pythonLibrary.getPythonPathDirectory();
          entries.add(pythonPathEntry);
          return rule.getDeps();
        }

        return ImmutableSet.of();
      }

    }.start();

    return entries.build();
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(main);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.setInput("main", main);
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    // TODO(mbolin): Package Python code, if appropriate. There does not appear to be a standard
    // cross-platform way to do this.
    return ImmutableList.of();
  }
}
