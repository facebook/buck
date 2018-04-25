/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;

public class TestBuildRuleCreationContextFactory {

  public static BuildRuleCreationContext create(
      BuildRuleResolver buildRuleResolver, ProjectFilesystem projectFilesystem) {
    return create(TargetGraph.EMPTY, buildRuleResolver, projectFilesystem);
  }

  public static BuildRuleCreationContext create(
      TargetGraph targetGraph,
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem projectFilesystem) {
    return create(
        targetGraph, buildRuleResolver, projectFilesystem, new ToolchainProviderBuilder().build());
  }

  public static BuildRuleCreationContext create(
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider) {
    return create(TargetGraph.EMPTY, buildRuleResolver, projectFilesystem, toolchainProvider);
  }

  public static BuildRuleCreationContext create(
      TargetGraph targetGraph,
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider) {
    return ImmutableBuildRuleCreationContext.of(
        targetGraph,
        buildRuleResolver,
        projectFilesystem,
        TestCellBuilder.createCellRoots(projectFilesystem),
        toolchainProvider);
  }
}
