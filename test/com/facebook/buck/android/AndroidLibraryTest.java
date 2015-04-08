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

import static org.junit.Assert.assertNotNull;

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class AndroidLibraryTest {

  @Test
  public void testAndroidAnnotation() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget processorTarget = BuildTargetFactory.newInstance("//java/processor:processor");
    BuildRule processorRule = JavaLibraryBuilder
        .createBuilder(processorTarget)
        .addSrc(Paths.get("java/processor/processor.java"))
        .build(ruleResolver);

    BuildTarget libTarget = BuildTargetFactory.newInstance("//java/lib:lib");
    AndroidLibrary library = (AndroidLibrary) AndroidLibraryBuilder
        .createBuilder(libTarget)
        .addProcessor("MyProcessor")
        .addProcessorBuildTarget(processorRule.getBuildTarget())
        .build(ruleResolver);

    AnnotationProcessingParams processingParams = library.getAnnotationProcessingParams();
    assertNotNull(processingParams.getGeneratedSourceFolderName());
  }
}
