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

package com.facebook.buck.apple;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Captures information about a core data model.
 * <p>
 * Example rule:
 * <pre>
 * core_data_model(
 *   name='data_model',
 *   path='Model.xcdatamodel'
 * )
 * </pre>
 */
public class CoreDataModel extends AbstractBuildRule {

  private final Supplier<ImmutableCollection<Path>> inputPathsSupplier;
  private final Path path;

  CoreDataModel(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Supplier<ImmutableCollection<Path>> inputPathsSupplier,
      Path path) {
    super(params, resolver);
    this.inputPathsSupplier = inputPathsSupplier;
    this.path = path;
  }

  /**
   * @return the path to the model file.
   */
  public Path getPath() {
    return path;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return inputPathsSupplier.get();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // TODO(user): add build steps to fix T4146823
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

}
