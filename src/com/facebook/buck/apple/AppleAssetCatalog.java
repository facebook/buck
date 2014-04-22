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

import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Captures information about an asset catalog.
 * <p>
 * Example rule:
 * <pre>
 * apple_asset_catalog(
 *   name='asset_catalog',
 *   dirs=['Backgrounds.xcassets', 'OtherImages.xcassets'],
 *   copy_to_bundles=True
 * )
 * </pre>
 */
public class AppleAssetCatalog extends AbstractBuildable {

  private final Supplier<Collection<Path>> inputPathsSupplier;
  private final ImmutableSet<Path> dirs;
  private final boolean copyToBundles;

  AppleAssetCatalog(
      Supplier<Collection<Path>> inputPathsSupplier,
      AppleAssetCatalogDescription.Arg args) {
    Preconditions.checkArgument(Iterables.all(args.dirs, new Predicate<Path>() {
              @Override
              public boolean apply(@Nullable Path input) {
                return input.toString().endsWith(".xcassets");
              }
            }));
    this.inputPathsSupplier = Preconditions.checkNotNull(inputPathsSupplier);
    this.dirs = ImmutableSet.copyOf(args.dirs);
    this.copyToBundles = args.copyToBundles.or(Boolean.FALSE);
  }

  /**
   * @return the path to the asset catalog.
   */
  public ImmutableSet<Path> getDirs() {
    return dirs;
  }

  /**
   * @return whether this asset catalog should be copied to its sibling bundle rather than the root
   *   resource output directory (or to Assets.car)
   */
  public boolean getCopyToBundles() {
    return copyToBundles;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return inputPathsSupplier.get();
  }

  @Override
  public List<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // This rule does not perform any build steps. Rather, the top-level binary target will
    // coalesce all asset catalog rules and build them together.
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder.set("copyToBundles", copyToBundles);
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }
}
