/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.google.common.base.Optional;

public class AndroidResourceBuildRuleFactory extends AbstractBuildRuleFactory<AndroidResourceRule.Builder> {

  @Override
  public AndroidResourceRule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return AndroidResourceRule.newAndroidResourceRuleBuilder(params);
  }

  @Override
  protected void amendBuilder(AndroidResourceRule.Builder builder, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    // res
    Optional<String> res = params.getOptionalStringAttribute("res");
    if (res.isPresent()) {
      // TODO(mbolin): Modify this check for descendant-ness rather than child-ness.
      // if (!buildDirectory.equals(res.getParentFile())) {
      //   throw new RuntimeException(res + " is not an immediate child of " + buildDirectory);
      // }
      String resDir = params.resolveDirectoryPathRelativeToBuildFileDirectory(res.get());
      builder.setRes(resDir);
    }

    // res_srcs
    for (String resSrc : params.getOptionalListAttribute("res_srcs")) {
      String relativePath = params.resolveFilePathRelativeToBuildFileDirectory(resSrc);
      builder.addResSrc(relativePath);
    }

    // package
    Optional<String> rDotJavaPackage = params.getOptionalStringAttribute("package");
    if (rDotJavaPackage.isPresent()) {
      builder.setRDotJavaPackage(rDotJavaPackage.get());
    }

    // assets
    Optional<String> assets = params.getOptionalStringAttribute("assets");
    if (assets.isPresent()) {
      String assetsDir = params.resolveDirectoryPathRelativeToBuildFileDirectory(assets.get());
      builder.setAssetsDirectory(assetsDir);
    }

    // assets_srcs
    for (String assetsSrc : params.getOptionalListAttribute("assets_srcs")) {
      String relativePath = params.resolveFilePathRelativeToBuildFileDirectory(assetsSrc);
      builder.addAssetsSrc(relativePath);
    }

    // manifest
    Optional<String> manifestFile = params.getOptionalStringAttribute("manifest");
    if (manifestFile.isPresent()) {
      String manifestFilePath = params.resolveFilePathRelativeToBuildFileDirectory(
          manifestFile.get());
      builder.setManifestFile(manifestFilePath);
    }

    builder.setHasWhitelistedStrings(params.getBooleanAttribute("has_whitelisted_strings"));
  }
}
