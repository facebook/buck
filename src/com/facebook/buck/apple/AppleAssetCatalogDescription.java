/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;

import java.nio.file.Path;
import java.util.Set;

/**
 * Description for an apple_asset_catalog rule, which identifies an asset
 * catalog for an iOS or Mac OS X library or binary.
 */
public class AppleAssetCatalogDescription implements Description<AppleAssetCatalogDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_asset_catalog");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AppleAssetCatalog createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ProjectFilesystem projectFilesystem = params.getProjectFilesystem();
    Supplier<ImmutableCollection<Path>> inputPathsSupplier =
        RuleUtils.subpathsOfPathsSupplier(projectFilesystem, args.dirs);
    return new AppleAssetCatalog(
        params,
        new SourcePathResolver(resolver),
        inputPathsSupplier,
        args);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Set<Path> dirs;
    public Optional<Boolean> copyToBundles;

    public boolean getCopyToBundles() {
      return copyToBundles.or(false);
    }
  }
}
