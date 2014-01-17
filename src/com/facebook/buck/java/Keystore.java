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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class Keystore extends AbstractBuildable {

  private final BuildTarget buildTarget;
  private final Path pathToStore;
  private final Path pathToProperties;

  protected Keystore(BuildTarget buildTarget, Path store, Path properties) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.pathToStore = Preconditions.checkNotNull(store);
    this.pathToProperties = Preconditions.checkNotNull(properties);
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(pathToStore, pathToProperties);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public Path getPathToStore() {
    return pathToStore;
  }

  public Path getPathToPropertiesFile() {
    return pathToProperties;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    // Nothing to build: this is like a glorified exported_deps() rule.
    return ImmutableList.of();
  }

  public static Builder newKeystoreBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder {

    private Path pathToStore;
    private Path pathToProperties;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.KEYSTORE;
    }

    @Override
    protected Keystore newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new Keystore(params.getBuildTarget(), pathToStore, pathToProperties);
    }

    public Builder setStore(Path pathToStore) {
      this.pathToStore = pathToStore;
      return this;
    }

    public Builder setProperties(Path pathToProperties) {
      this.pathToProperties = pathToProperties;
      return this;
    }

  }
}
