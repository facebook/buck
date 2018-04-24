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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import org.immutables.value.Value;

/**
 * Contains common objects used during {@link BuildRule} creation in {@link
 * Description#createBuildRule}.
 */
@Value.Immutable(builder = false, copy = false)
public interface BuildRuleCreationContext {
  @Value.Parameter
  TargetGraph getTargetGraph();

  @Value.Parameter
  BuildRuleResolver getBuildRuleResolver();

  @Value.Parameter
  ProjectFilesystem getProjectFilesystem();

  @Value.Parameter
  CellPathResolver getCellPathResolver();

  @Value.Parameter
  ToolchainProvider getToolchainProvider();
}
