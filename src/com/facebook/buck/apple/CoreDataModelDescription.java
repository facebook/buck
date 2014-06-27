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

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * Description for a core_data_model rule, which identifies a model file
 * for use with Apple's Core Data.
 */
public class CoreDataModelDescription implements Description<CoreDataModelDescription.Arg> {
  public static final BuildRuleType TYPE = new BuildRuleType("core_data_model");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> CoreDataModel createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ProjectFilesystem projectFilesystem = params.getProjectFilesystem();
    Supplier<ImmutableCollection<Path>> inputPathsSupplier = RuleUtils.subpathsOfPathsSupplier(
        projectFilesystem,
        ImmutableSet.of(args.path));
    return new CoreDataModel(params, inputPathsSupplier, args);
  }

  public static class Arg implements ConstructorArg {
    /**
     * @see com.facebook.buck.apple.XcodeRuleConfiguration#fromRawJsonStructure
     */
    public Path path;
  }
}
