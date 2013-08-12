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
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;

public class KeystoreRule extends AbstractCachingBuildRule {

  private final String pathToStore;
  private final String pathToProperties;

  protected KeystoreRule(BuildRuleParams buildRuleParams,
      String store,
      String properties) {
    super(buildRuleParams);
    this.pathToStore = Preconditions.checkNotNull(store);
    this.pathToProperties = Preconditions.checkNotNull(properties);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.KEYSTORE;
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return ImmutableList.of(pathToStore, pathToProperties);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("store", pathToStore)
        .set("properties", pathToProperties);
  }

  public String getPathToStore() {
    return pathToStore;
  }

  public String getPathToPropertiesFile() {
    return pathToProperties;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context) throws IOException {
    // Nothing to build: this is like a glorified export_deps() rule.
    return ImmutableList.of();
  }

  public static Builder newKeystoreBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<KeystoreRule> {

    private String pathToStore;
    private String pathToProperties;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public KeystoreRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new KeystoreRule(buildRuleParams, pathToStore, pathToProperties);
    }

    public Builder setStore(String pathToStore) {
      this.pathToStore = pathToStore;
      return this;
    }

    public Builder setProperties(String pathToProperties) {
      this.pathToProperties = pathToProperties;
      return this;
    }

  }
}
