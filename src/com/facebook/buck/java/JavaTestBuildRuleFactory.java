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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractTestRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.util.List;

public class JavaTestBuildRuleFactory extends AbstractTestRuleFactory<JavaTestRule.Builder> {

  @Override
  public JavaTestRule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return JavaTestRule.newJavaTestRuleBuilder(params);
  }

  @Override
  protected void amendBuilder(JavaTestRule.Builder builder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    JavaLibraryBuildRuleFactory.extractAnnotationProcessorParameters(
        builder.getAnnotationProcessingBuilder(), builder, params);

    // source
    Optional<String> sourceLevel = params.getOptionalStringAttribute("source");
    if (sourceLevel.isPresent()) {
      builder.setSourceLevel(sourceLevel.get());
    }

    // target
    Optional<String> targetLevel = params.getOptionalStringAttribute("target");
    if (targetLevel.isPresent()) {
      builder.setTargetLevel(targetLevel.get());
    }

    // vm_args
    builder.setVmArgs(params.getOptionalListAttribute("vm_args"));

    // source_under_test
    Function<String, BuildTarget> contextualBuildParser = createBuildTargetParseFunction(params);
    ImmutableSet<BuildTarget> sourceUnderTest = ImmutableSet.copyOf(Iterables.transform(
        params.getOptionalListAttribute("source_under_test"), contextualBuildParser));
    builder.setSourceUnderTest(sourceUnderTest);

    // contacts
    List<String> contacts = params.getOptionalListAttribute("contacts");
    builder.setContacts(ImmutableSet.copyOf(contacts));
  }
}
