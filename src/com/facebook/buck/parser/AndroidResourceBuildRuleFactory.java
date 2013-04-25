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

package com.facebook.buck.parser;

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AndroidResourceRule;
import com.google.common.base.Optional;

public class AndroidResourceBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  public AbstractBuildRuleBuilder newBuilder() {
    return AndroidResourceRule.newAndroidResourceRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
                              BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    AndroidResourceRule.Builder builder = ((AndroidResourceRule.Builder)abstractBuilder);

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

    // manifest
    Optional<String> manifestFile = params.getOptionalStringAttribute("manifest");
    if (manifestFile.isPresent()) {
      String manifestFilePath = params.resolveFilePathRelativeToBuildFileDirectory(
          manifestFile.get());
      builder.setManifestFile(manifestFilePath);
    }
  }
}
