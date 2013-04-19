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
import com.facebook.buck.rules.AndroidManifestRule;

public final class AndroidManifestBuildRuleFactory extends AbstractBuildRuleFactory {

  static final String MANIFEST_FILENAME = "AndroidManifest.xml";

  @Override
  public AndroidManifestRule.Builder newBuilder() {
    return AndroidManifestRule.newManifestMergeRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
                              BuildRuleFactoryParams params) {
    AndroidManifestRule.Builder builder = ((AndroidManifestRule.Builder) abstractBuilder);

    // manifest file
    String manifestPath =
        params.resolveAndCreateFilePathRelativeToBuildFileDirectory(MANIFEST_FILENAME);
    builder.setManifestFile(manifestPath);

    // skeleton file
    String skeletonFile = params.getRequiredStringAttribute("skeleton");
    String skeletonPath = params.resolveFilePathRelativeToBuildFileDirectory(skeletonFile);
    builder.setSkeletonFile(skeletonPath);

    // no_dx
    for (String entry: params.getOptionalListAttribute("no_dx")) {
      builder.addBuildRuleToExcludeFromDex(entry);
    }
  }
}
