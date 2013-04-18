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
import com.facebook.buck.rules.JavaBinaryRule;
import com.google.common.base.Optional;


public class JavaBinaryBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  JavaBinaryRule.Builder newBuilder() {
    return JavaBinaryRule.newJavaBinaryRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    JavaBinaryRule.Builder builder = (JavaBinaryRule.Builder)abstractBuilder;

    // main_class
    Optional<String> mainClass = params.getOptionalStringAttribute("main_class");
    if (mainClass.isPresent()) {
      builder.setMainClass(mainClass.get());
    }

    // manifest_file
    Optional<String> manifestFile = params.getOptionalStringAttribute("manifest_file");
    if (manifestFile.isPresent()) {
      String manifestFilePath = params.resolveFilePathRelativeToBuildFileDirectory(
          manifestFile.get());
      builder.setManifest(manifestFilePath);
    }

  }

}
