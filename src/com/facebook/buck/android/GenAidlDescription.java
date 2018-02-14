/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

public class GenAidlDescription implements Description<GenAidlDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  public GenAidlDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<GenAidlDescriptionArg> getConstructorArgType() {
    return GenAidlDescriptionArg.class;
  }

  @Override
  public GenAidl createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GenAidlDescriptionArg args) {
    return new GenAidl(
        buildTarget,
        projectFilesystem,
        toolchainProvider,
        params,
        args.getAidl(),
        args.getImportPath());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGenAidlDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getAidl();

    // import_path is an anomaly: it is a path that is relative to the project root rather than
    // relative to the build file directory.
    String getImportPath();
  }
}
