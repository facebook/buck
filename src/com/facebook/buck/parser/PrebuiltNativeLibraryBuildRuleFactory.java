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
import com.facebook.buck.rules.PrebuiltNativeLibraryBuildRule;

public class PrebuiltNativeLibraryBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  AbstractBuildRuleBuilder newBuilder() {
    return PrebuiltNativeLibraryBuildRule.newPrebuiltNativeLibrary();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    PrebuiltNativeLibraryBuildRule.Builder builder = ((PrebuiltNativeLibraryBuildRule.Builder)abstractBuilder);

    // native_libs
    String nativeLibs = params.getRequiredStringAttribute("native_libs");
    String nativeLibsDir = params.resolveDirectoryPathRelativeToBuildFileDirectory(nativeLibs);
    builder.setNativeLibsDirectory(nativeLibsDir);
  }
}
