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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

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
public class AppleAssetCatalog extends NoopBuildRule {

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final Supplier<ImmutableCollection<Path>> inputPathsSupplier;
  private final ImmutableSet<Path> dirs;
  @AddToRuleKey
  private final boolean copyToBundles;

  AppleAssetCatalog(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Supplier<ImmutableCollection<Path>> inputPathsSupplier,
      AppleAssetCatalogDescription.Arg args) {
    super(params, resolver);
    Preconditions.checkArgument(Iterables.all(args.dirs, new Predicate<Path>() {
              @Override
              public boolean apply(Path input) {
                return input.toString().endsWith(".xcassets");
              }
            }));
    this.inputPathsSupplier = inputPathsSupplier;
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
}
