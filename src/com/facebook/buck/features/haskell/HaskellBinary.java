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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.tool.BinaryWrapperRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;

public class HaskellBinary extends BinaryWrapperRule implements HaskellIdeDep {

  private final ImmutableSet<BuildRule> deps;
  private final Tool binary;
  private final SourcePath output;
  private final SourceSortedSet srcs;
  private final Collection<String> compilerFlags;

  public HaskellBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSet<BuildRule> deps,
      SourceSortedSet srcs,
      Collection<String> compilerFlags,
      Tool binary,
      SourcePath output) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.deps = deps;
    this.binary = binary;
    this.output = output;
    this.srcs = srcs;
    this.compilerFlags = compilerFlags;
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return binary;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public ImmutableSet<BuildRule> getBinaryDeps() {
    return deps;
  }

  @Override
  public Iterable<BuildRule> getIdeDeps(HaskellPlatform platform) {
    return deps;
  }

  @Override
  public Collection<String> getIdeCompilerFlags() {
    return compilerFlags;
  }

  @Override
  public SourceSortedSet getIdeSources() {
    return srcs;
  }
}
