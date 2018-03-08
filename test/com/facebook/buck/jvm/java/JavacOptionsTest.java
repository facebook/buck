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

package com.facebook.buck.jvm.java;

import static org.easymock.EasyMock.createMock;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.Lists;
import java.util.Collections;
import org.hamcrest.Matcher;
import org.junit.Test;

public class JavacOptionsTest {

  @Test
  public void buildsAreDebugByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionFlags(options, hasItem("g"));
  }

  @Test
  public void spoolModeToDiskByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertThat(options.getSpoolMode(), is(JavacOptions.SpoolMode.INTERMEDIATE_TO_DISK));
  }

  @Test
  public void productionBuildsCanBeEnabled() {
    JavacOptions options = createStandardBuilder().setProductionBuild(true).build();

    assertOptionFlags(options, not(hasItem("g")));
  }

  @Test
  public void testDoesNotSetBootclasspathByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsHasNoKey(options, "bootclasspath");
  }

  @Test
  public void canSetBootclasspath() {
    JavacOptions options = createStandardBuilder().setBootclasspath("foo:bar").build();

    assertOptionsHasKeyValue(options, "bootclasspath", "foo:bar");
  }

  @Test
  public void shouldSetTheAnnotationSource() {
    AnnotationProcessingParams params =
        AnnotationProcessingParams.builder()
            .setLegacySafeAnnotationProcessors(Collections.emptySet())
            .setLegacyAnnotationProcessorNames(Collections.singleton("processor"))
            .setProcessOnly(true)
            .setProjectFilesystem(FakeProjectFilesystem.createJavaOnlyFilesystem())
            .build();

    JavacOptions options = createStandardBuilder().setAnnotationProcessingParams(params).build();

    assertOptionFlags(options, hasItem("proc:only"));
  }

  @Test
  public void shouldAddAllAddedAnnotationProcessors() {
    AnnotationProcessingParams params =
        AnnotationProcessingParams.builder()
            .setLegacyAnnotationProcessorDeps(Collections.emptySet())
            .setLegacyAnnotationProcessorNames(Lists.newArrayList("myproc", "theirproc"))
            .setProcessOnly(true)
            .setProjectFilesystem(FakeProjectFilesystem.createJavaOnlyFilesystem())
            .build();

    JavacOptions options = createStandardBuilder().setAnnotationProcessingParams(params).build();

    assertOptionsHasKeyValue(options, "processor", "myproc,theirproc");
  }

  @Test
  public void shouldDisableAnnotationProcessingIfNoProcessorsSpecified() {
    JavacOptions options = createStandardBuilder().build();
    assertOptionFlags(options, hasItem("proc:none"));
  }

  @Test
  public void sourceAndTarget7ByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsHasKeyValue(options, "source", "7");
    assertOptionsHasKeyValue(options, "target", "7");
  }

  @Test
  public void shouldSetSourceAndTargetLevels() {
    JavacOptions original = createStandardBuilder().setSourceLevel("8").setTargetLevel("5").build();

    JavacOptions copy = JavacOptions.builder(original).build();
    assertOptionsHasKeyValue(copy, "source", "8");
    assertOptionsHasKeyValue(copy, "target", "5");
  }

  @Test
  public void shouldAddABootClasspathIfTheMapContainsOne() {
    JavacOptions options =
        createStandardBuilder()
            .setSourceLevel("5")
            .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
            .build();

    assertOptionsHasKeyValue(options, "bootclasspath", "some-magic.jar:also.jar");
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfOneIsSet() {
    String expectedBootClasspath = "some-magic.jar:also.jar";
    JavacOptions options =
        createStandardBuilder()
            .setBootclasspath(expectedBootClasspath)
            .setSourceLevel("5")
            .putSourceToBootclasspath("5", "not-the-right-path.jar")
            .build();

    assertOptionsHasKeyValue(options, "bootclasspath", expectedBootClasspath);
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfSourceLevelHasNoMapping() {
    JavacOptions options =
        createStandardBuilder()
            .setBootclasspath("cake.jar")
            .setSourceLevel("6")
            .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
            .build();

    assertOptionsHasKeyValue(options, "bootclasspath", "cake.jar");
  }

  @Test
  public void shouldCopyMapOfSourceLevelToBootclassPathWhenBuildingNewJavacOptions() {
    JavacOptions original =
        createStandardBuilder()
            .setSourceLevel("5")
            .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
            .build();

    JavacOptions copy = JavacOptions.builder(original).build();
    assertOptionsHasKeyValue(copy, "bootclasspath", "some-magic.jar:also.jar");
  }

  @Test
  public void shouldIncoporateExtraOptionsInOutput() {
    JavacOptions options = createStandardBuilder().addExtraArguments("-Xfoobar").build();

    assertOptionsHasExtra(options, "-Xfoobar");
  }

  private JavacOptions.Builder createStandardBuilder() {
    return JavacOptions.builderForUseInJavaBuckConfig();
  }

  private void assertOptionFlags(JavacOptions options, Matcher<Iterable<? super String>> matcher) {
    assertThat(visitOptions(options).flags, matcher);
  }

  private OptionAccumulator visitOptions(JavacOptions options) {
    OptionAccumulator optionsConsumer = new OptionAccumulator();
    options.appendOptionsTo(
        optionsConsumer, createMock(SourcePathResolver.class), createMock(ProjectFilesystem.class));
    return optionsConsumer;
  }

  private void assertOptionsHasNoKey(JavacOptions options, String optionKey) {
    assertThat(visitOptions(options).keyVals, not(hasKey(optionKey)));
  }

  private void assertOptionsHasExtra(JavacOptions options, String extra) {
    assertThat(visitOptions(options).extras, hasItem(extra));
  }

  private void assertOptionsHasKeyValue(
      JavacOptions options, String optionName, String optionValue) {
    assertThat(visitOptions(options).keyVals, hasEntry(optionName, optionValue));
  }
}
