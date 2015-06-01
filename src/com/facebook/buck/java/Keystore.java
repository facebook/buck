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

package com.facebook.buck.java;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class Keystore extends AbstractBuildRule {

  @AddToRuleKey
  private final Path pathToStore;
  @AddToRuleKey
  private final Path pathToProperties;

  public Keystore(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path store,
      Path properties) {
    super(params, resolver);
    this.pathToStore = store;
    this.pathToProperties = properties;
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }

  public Path getPathToStore() {
    return pathToStore;
  }

  public Path getPathToPropertiesFile() {
    return pathToProperties;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // Nothing to build: this is like a glorified exported_deps() rule.
    return ImmutableList.of();
  }
}
