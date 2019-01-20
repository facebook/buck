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

package com.facebook.buck.features.filegroup;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import org.immutables.value.Value;

public class FilegroupDescription implements DescriptionWithTargetGraph<FileGroupDescriptionArg> {

  @Override
  public Class<FileGroupDescriptionArg> getConstructorArgType() {
    return FileGroupDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      FileGroupDescriptionArg args) {
    String name = args.getName();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(context.getActionGraphBuilder());
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    return new Filegroup(buildTarget, projectFilesystem, ruleFinder, name, args.getSrcs());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractFileGroupDescriptionArg extends CommonDescriptionArg, HasSrcs {}
}
