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

package com.facebook.buck.apple;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.collect.ImmutableSet;

/**
 * Description for an xcode_prebuild_script rule which runs a shell script before the Apple target
 * that depends on it is built.
 *
 * <p>Example rule:
 *
 * <pre>
 * xcode_prebuild_script(
 *   name = 'register_app',
 *   cmd = 'register_app.sh',
 * )
 * </pre>
 *
 * <p>This rule is a hack and in the long-term should be replaced with a genrule that works in both
 * Buck and Xcode build. Those rules do nothing when building with Buck.
 */
public class XcodePrebuildScriptDescription
    implements Description<XcodeScriptDescriptionArg>, Flavored {

  @Override
  public Class<XcodeScriptDescriptionArg> getConstructorArgType() {
    return XcodeScriptDescriptionArg.class;
  }

  @Override
  public NoopBuildRuleWithDeclaredAndExtraDeps createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      XcodeScriptDescriptionArg args) {
    return new NoopBuildRuleWithDeclaredAndExtraDeps(buildTarget, projectFilesystem, params);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return true;
  }
}
