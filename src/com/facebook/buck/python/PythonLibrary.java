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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class PythonLibrary extends AbstractBuildRule implements PythonPackagable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> resources;

  protected PythonLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources) {
    super(params);
    this.srcs = Preconditions.checkNotNull(srcs);
    this.resources = Preconditions.checkNotNull(resources);
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  /**
   * Convert a set of SourcePaths to a map of Paths mapped to themselves,
   * appropriate for being put into a PythonPackageComponents instance.
   * <p>
   * TODO(#4446762): Currently, the location of sources in the top-level binary
   * matches the repo-relative path exactly as we form this from the SourcePath
   * objects we get for the sources list.  In the future, we should have a way
   * to customize how these files get laid out, as this approach doesn't lend
   * itself well to generated sources and cases where we don't want the repo layout
   * to match the binary location.
   */
  private ImmutableMap<Path, Path> getPathMapFromSourcePaths(
      ImmutableSet<SourcePath> sourcePaths) {
    ImmutableMap.Builder<Path, Path> paths = ImmutableMap.builder();
    for (SourcePath src : sourcePaths) {
      Path path = src.resolve();
      paths.put(path, path);
    }
    return paths.build();
  }

  /**
   * Return the components to contribute to the top-level python package.
   */
  @Override
  public PythonPackageComponents getPythonPackageComponents() {
    return new PythonPackageComponents(
        getPathMapFromSourcePaths(srcs),
        getPathMapFromSourcePaths(resources),
        ImmutableMap.<Path, Path>of());
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(
        Iterables.concat(srcs, resources));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

}
