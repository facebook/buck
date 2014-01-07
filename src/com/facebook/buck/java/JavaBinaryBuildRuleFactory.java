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

package com.facebook.buck.java;

import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.google.common.base.Optional;

import java.nio.file.Path;


public class JavaBinaryBuildRuleFactory extends AbstractBuildRuleFactory<JavaBinaryRule.Builder> {

  @Override
  public JavaBinaryRule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return JavaBinaryRule.newJavaBinaryRuleBuilder(params);
  }

  @Override
  protected void amendBuilder(JavaBinaryRule.Builder builder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    // main_class
    Optional<String> mainClass = params.getOptionalStringAttribute("main_class");
    if (mainClass.isPresent()) {
      builder.setMainClass(mainClass.get());
    }

    // manifest_file
    Optional<String> manifestFile = params.getOptionalStringAttribute("manifest_file");
    if (manifestFile.isPresent()) {
      Path manifestFilePath = params.resolveFilePathRelativeToBuildFileDirectory(
          manifestFile.get());
      builder.setManifest(manifestFilePath);
    }

  }

}
