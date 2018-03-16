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

package com.facebook.buck.shell.filegroup;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

public class FilegroupDescription implements Description<FileGroupDescriptionArg> {

  @Override
  public Class<FileGroupDescriptionArg> getConstructorArgType() {
    return FileGroupDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      FileGroupDescriptionArg args) {
    String name = args.getName();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(context.getBuildRuleResolver());
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    return new Filegroup(buildTarget, projectFilesystem, ruleFinder, name, args.getSrcs());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractFileGroupDescriptionArg extends CommonDescriptionArg, HasSrcs {}
}
