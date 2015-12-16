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

package com.facebook.buck.lua;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public abstract class LuaBinary
    extends AbstractBuildRule
    implements BinaryBuildRule, HasRuntimeDeps {

  private final Tool lua;
  private final String mainModule;
  private final LuaPackageComponents components;
  @AddToRuleKey
  private final String extension;

  public LuaBinary(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool lua,
      String mainModule,
      LuaPackageComponents components,
      String extension) {
    super(buildRuleParams, resolver);
    this.lua = lua;
    this.mainModule = mainModule;
    this.components = components;
    this.extension = extension;
  }

  protected final Path getBinPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s" + extension);
  }

  @Override
  public final Path getPathToOutput() {
    return getBinPath();
  }

  @VisibleForTesting
  protected Tool getLua() {
    return lua;
  }

  @VisibleForTesting
  protected final String getMainModule() {
    return mainModule;
  }

  @VisibleForTesting
  protected final LuaPackageComponents getComponents() {
    return components;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return getDeclaredDeps();
  }

}
