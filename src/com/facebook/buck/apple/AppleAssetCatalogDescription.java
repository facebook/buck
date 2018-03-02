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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Description for an apple_asset_catalog rule, which identifies an asset catalog for an iOS or Mac
 * OS X library or binary.
 */
public class AppleAssetCatalogDescription implements Description<AppleAssetCatalogDescriptionArg> {

  @Override
  public Class<AppleAssetCatalogDescriptionArg> getConstructorArgType() {
    return AppleAssetCatalogDescriptionArg.class;
  }

  @Override
  public NoopBuildRuleWithDeclaredAndExtraDeps createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleAssetCatalogDescriptionArg args) {
    return new NoopBuildRuleWithDeclaredAndExtraDeps(
        buildTarget, context.getProjectFilesystem(), params);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleAssetCatalogDescriptionArg extends CommonDescriptionArg {
    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getDirs();

    Optional<String> getAppIcon();

    Optional<String> getLaunchImage();
  }
}
