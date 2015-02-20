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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class AndroidLibraryTest {

  @Test
  public void testGetInputsToCompareToOuts() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule androidLibraryBuilderBar = getAndroidLibraryRuleBar(ruleResolver);
    BuildRule androidLibraryBuilderFoo = getAndroidLibraryRuleFoo(ruleResolver);
    BuildContext context = createMock(BuildContext.class);
    replay(context);

    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should include manifest and src.",
        ImmutableList.of(
            Paths.get("java/src/com/foo/Foo.java"),
            Paths.get("java/src/com/foo/AndroidManifest.xml")),
        androidLibraryBuilderFoo.getInputs());

    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should include only src.",
        ImmutableList.of(Paths.get("java/src/com/bar/Bar.java")),
        androidLibraryBuilderBar.getInputs());

    assertEquals(
        "foo's exported deps should include bar",
        ImmutableSet.of(androidLibraryBuilderBar),
        ((AndroidLibrary) (androidLibraryBuilderFoo)).getExportedDeps());
  }

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


  private BuildRule getAndroidLibraryRuleFoo(BuildRuleResolver ruleResolver) {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRule libraryRule = JavaLibraryBuilder
        .createBuilder(BuildTarget.builder("//java/src/com/bar", "bar").build())
        .build(new BuildRuleResolver());

    return AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/foo:foo"))
        .addSrc(Paths.get("java/src/com/foo/Foo.java"))
        .setManifestFile(
            new PathSourcePath(
                projectFilesystem,
                Paths.get("java/src/com/foo/AndroidManifest.xml")))
        .addExportedDep(libraryRule.getBuildTarget())
        .addDep(libraryRule.getBuildTarget())
        .build(ruleResolver);
  }

  private BuildRule getAndroidLibraryRuleBar(BuildRuleResolver ruleResolver) {
    return AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/bar:bar"))
        .addSrc(Paths.get("java/src/com/bar/Bar.java"))
        // leave the manifest empty on purpose.
        .build(ruleResolver);
  }
}
