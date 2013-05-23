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

import com.facebook.buck.parser.AbstractBuildRuleFactory;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.google.common.base.Optional;


public class PrebuiltJarBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  public PrebuiltJarRule.Builder newBuilder() {
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
    builder.setSourceJar(
        sourceJar.transform(params.getResolveFilePathRelativeToBuildFileDirectoryTransform()));

    // javadoc_url
    Optional<String> javadocUrl = params.getOptionalStringAttribute("javadoc_url");
    builder.setJavadocUrl(javadocUrl);
  }
}
