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
import com.facebook.buck.rules.PrebuiltJarRule;
import com.google.common.base.Optional;


final class PrebuiltJarBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  PrebuiltJarRule.Builder newBuilder() {
    return PrebuiltJarRule.newPrebuiltJarRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
      BuildRuleFactoryParams params) {
    PrebuiltJarRule.Builder builder = ((PrebuiltJarRule.Builder)abstractBuilder);

    // binary_jar
    String binaryJar = params.getRequiredStringAttribute("binary_jar");
    String binaryJarFile = params.resolveFilePathRelativeToBuildFileDirectory(binaryJar);
    builder.setBinaryJar(binaryJarFile);

    // source_jar
    Optional<String> sourceJar = params.getOptionalStringAttribute("source_jar");
    if (sourceJar.isPresent()) {
      String sourceJarFile = params.resolveFilePathRelativeToBuildFileDirectory(sourceJar.get());
      builder.setSourceJar(sourceJarFile);
    }

    // javadoc_url
    Optional<String> javadocUrl = params.getOptionalStringAttribute("javadoc_url");
    if (javadocUrl.isPresent()) {
      builder.setJavadocUrl(javadocUrl.get());
    }
  }
}
