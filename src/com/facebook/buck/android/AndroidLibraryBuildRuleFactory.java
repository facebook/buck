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

import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.java.JavaLibraryBuildRuleFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.google.common.base.Optional;

public class AndroidLibraryBuildRuleFactory extends JavaLibraryBuildRuleFactory {

  @Override
  public AndroidLibraryRule.Builder newBuilder() {
    return AndroidLibraryRule.newAndroidLibraryRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    super.amendBuilder(abstractBuilder, params);
    AndroidLibraryRule.Builder builder = ((AndroidLibraryRule.Builder)abstractBuilder);

    // manifest
    Optional<String> manifestFile = params.getOptionalStringAttribute("manifest");
    builder.setManifestFile(
        manifestFile.transform(params.getResolveFilePathRelativeToBuildFileDirectoryTransform()));
  }
}
