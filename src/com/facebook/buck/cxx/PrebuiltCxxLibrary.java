/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.CanProvideNativeLinkTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public abstract class PrebuiltCxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibrary, CanProvideNativeLinkTarget {

  PrebuiltCxxLibrary(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
    super(buildTarget, projectFilesystem, params);
  }

  public abstract ImmutableList<Arg> getExportedLinkerArgs(CxxPlatform cxxPlatform);

  public abstract ImmutableList<String> getExportedPostLinkerFlags(CxxPlatform cxxPlatform);

  abstract Optional<SourcePath> getStaticLibrary(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  abstract Optional<SourcePath> getStaticPicLibrary(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);
}
