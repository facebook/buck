/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;

public class JavacOptionsTest {

  @Test
  public void buildsAreDebugByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsContains(options, "-g");
  }

  @Test
  public void productionBuildsCanBeEnabled() {
    JavacOptions options = createStandardBuilder()
        .setProductionBuild(true)
        .build();

    assertOptionsDoesNotContain(options, "-g");
  }

  @Test
  public void testDoesNotSetBootclasspathByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsDoesNotContain(options, "-bootclasspath");
  }

  @Test
  public void canSetBootclasspath() {
    JavacOptions options = createStandardBuilder()
        .setBootclasspath("foo:bar")
        .build();

    assertOptionsContains(options, "-bootclasspath foo:bar");
  }

  @Test
  public void shouldSetTheAnnotationSource() {
    AnnotationProcessingParams params = new AnnotationProcessingParams.Builder()
        .addAllProcessors(Collections.singleton("processor"))
        .setProcessOnly(true)
        .build();

    JavacOptions options = createStandardBuilder()
        .setAnnotationProcessingParams(params)
        .build();

    assertOptionsContains(options, "-proc:only");
  }

  @Test
  public void shouldAddAllAddedAnnotationProcessors() {
    AnnotationProcessingParams params = new AnnotationProcessingParams.Builder()
        .addAllProcessors(Lists.newArrayList("myproc", "theirproc"))
        .setProcessOnly(true)
        .build();

    JavacOptions options = createStandardBuilder()
        .setAnnotationProcessingParams(params)
        .build();

    assertOptionsContains(options, "-processor myproc,theirproc");
  }

  @Test
  public void shouldSetSourceAndTargetLevels() {
    JavacOptions original = createStandardBuilder()
        .setSourceLevel("7")
        .setTargetLevel("5")
        .build();

    JavacOptions copy = JavacOptions.builder(original).build();

    assertOptionsContains(copy, "-source 7");
    assertOptionsContains(copy, "-target 5");
  }

  @Test
  public void shouldAddABootClasspathIfTheMapContainsOne() {
    JavacOptions options = createStandardBuilder()
        .setSourceLevel("5")
        .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
        .build();

    ImmutableList.Builder<String> allArgs = ImmutableList.builder();
    options.appendOptionsToList(allArgs, Functions.<Path>identity());

    assertOptionsContains(options, "-bootclasspath some-magic.jar:also.jar");
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfOneIsSet() {
    String expectedBootClasspath = "some-magic.jar:also.jar";
    JavacOptions options = createStandardBuilder()
        .setBootclasspath(expectedBootClasspath)
        .setSourceLevel("5")
        .putSourceToBootclasspath("5", "not-the-right-path.jar")
        .build();

    ImmutableList.Builder<String> allArgs = ImmutableList.builder();
    options.appendOptionsToList(allArgs, Functions.<Path>identity());

    ImmutableList<String> args = allArgs.build();
    int bootclasspathIndex = Iterables.indexOf(args, Predicates.equalTo("-bootclasspath"));
    assertNotEquals(-1, bootclasspathIndex);
    assertEquals(expectedBootClasspath, args.get(bootclasspathIndex + 1));
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfSourceLevelHasNoMapping() {
    JavacOptions options = createStandardBuilder()
        .setBootclasspath("cake.jar")
        .setSourceLevel("6")
        .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
        .build();

    ImmutableList.Builder<String> allArgs = ImmutableList.builder();
    options.appendOptionsToList(allArgs, Functions.<Path>identity());

    ImmutableList<String> args = allArgs.build();
    int bootclasspathIndex = Iterables.indexOf(args, Predicates.equalTo("-bootclasspath"));
    assertNotEquals(-1, bootclasspathIndex);
    assertEquals("cake.jar", args.get(bootclasspathIndex + 1));
  }

  @Test
  public void shouldCopyMapOfSourceLevelToBootclassPathWhenBuildingNewJavacOptions() {
    JavacOptions original = createStandardBuilder()
        .setSourceLevel("5")
        .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
        .build();

    JavacOptions copy = JavacOptions.builder(original).build();

    assertOptionsContains(copy, "-bootclasspath some-magic.jar:also.jar");
  }

  @Test
  public void shouldIncoporateExtraOptionsInOutput() {
    JavacOptions options = createStandardBuilder()
        .addExtraArguments("-Xfoobar")
        .build();

    assertOptionsContains(options, "-Xfoobar");
  }

  private void assertOptionsContains(JavacOptions options, String param) {
    String output = optionsAsString(options);

    assertTrue(String.format("Unable to find: %s in %s", param, output),
        output.contains(" " + param + " "));
  }

  private void assertOptionsDoesNotContain(JavacOptions options, String param) {
    String output = optionsAsString(options);

    assertFalse(
        String.format("Surprisingly and unexpectedly found: %s in %s", param, output),
        output.contains(" " + param + " "));
  }

  private String optionsAsString(JavacOptions options) {
    ImmutableList.Builder<String> paramBuilder = ImmutableList.builder();

    options.appendOptionsToList(
        paramBuilder, /* pathAbsolutifier */ IdentityPathAbsolutifier.getIdentityAbsolutifier());

    ImmutableList<String> params = paramBuilder.build();
    return " " + Joiner.on(" ").join(params) + " ";
  }

  private ImmutableJavacOptions.Builder createStandardBuilder() {
    return JavacOptions.builderForUseInJavaBuckConfig()
        .setSourceLevel("5")
        .setTargetLevel("5");
  }
}
