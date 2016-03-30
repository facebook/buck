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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.collect.Lists;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

public class JavacOptionsTest {

  @Test
  public void buildsAreDebugByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionFlags(options, hasItem("g"));
  }

  @Test
  public void productionBuildsCanBeEnabled() {
    JavacOptions options = createStandardBuilder()
        .setProductionBuild(true)
        .build();

    assertOptionFlags(options, not(hasItem("g")));
  }

  @Test
  public void testDoesNotSetBootclasspathByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsHasNoKey(options, "bootclasspath");
  }

  @Test
  public void canSetBootclasspath() {
    JavacOptions options = createStandardBuilder()
        .setBootclasspath("foo:bar")
        .build();

    assertOptionsHasKeyValue(options, "bootclasspath", "foo:bar");
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

    assertOptionFlags(options, hasItem("proc:only"));
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

    assertOptionsHasKeyValue(options, "processor", "myproc,theirproc");
  }

  @Test
  public void shouldSetSourceAndTargetLevels() {
    JavacOptions original = createStandardBuilder()
        .setSourceLevel("7")
        .setTargetLevel("5")
        .build();

    JavacOptions copy = JavacOptions.builder(original).build();
    assertOptionsHasKeyValue(copy, "source", "7");
    assertOptionsHasKeyValue(copy, "target", "5");
  }

  @Test
  public void shouldAddABootClasspathIfTheMapContainsOne() {
    JavacOptions options = createStandardBuilder()
        .setSourceLevel("5")
        .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
        .build();

    assertOptionsHasKeyValue(options, "bootclasspath", "some-magic.jar:also.jar");
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfOneIsSet() {
    String expectedBootClasspath = "some-magic.jar:also.jar";
    JavacOptions options = createStandardBuilder()
        .setBootclasspath(expectedBootClasspath)
        .setSourceLevel("5")
        .putSourceToBootclasspath("5", "not-the-right-path.jar")
        .build();

    assertOptionsHasKeyValue(options, "bootclasspath", expectedBootClasspath);
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfSourceLevelHasNoMapping() {
    JavacOptions options = createStandardBuilder()
        .setBootclasspath("cake.jar")
        .setSourceLevel("6")
        .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
        .build();

    assertOptionsHasKeyValue(options, "bootclasspath", "cake.jar");
  }

  @Test
  public void shouldCopyMapOfSourceLevelToBootclassPathWhenBuildingNewJavacOptions() {
    JavacOptions original = createStandardBuilder()
        .setSourceLevel("5")
        .putSourceToBootclasspath("5", "some-magic.jar:also.jar")
        .build();

    JavacOptions copy = JavacOptions.builder(original).build();
    assertOptionsHasKeyValue(copy, "bootclasspath", "some-magic.jar:also.jar");
  }

  @Test
  public void shouldIncoporateExtraOptionsInOutput() {
    JavacOptions options = createStandardBuilder()
        .addExtraArguments("-Xfoobar")
        .build();

    assertOptionsHasExtra(options, "-Xfoobar");
  }

  @Test
  public void externalJavacVersionIsReadFromStderrBecauseThatIsWhereJavacWritesIt()
      throws IOException {
    Platform current = Platform.detect();
    Assume.assumeTrue(current != Platform.WINDOWS && current != Platform.UNKNOWN);

    Path tempPath = Files.createTempFile("javac", "spoof");
    File tempFile = tempPath.toFile();
    tempFile.deleteOnExit();
    assertTrue(tempFile.setExecutable(true));
    // We could use the "-n" syntax, but that doesn't work on all variants of echo. Play it safe.
    Files.write(tempPath, "echo \"cover-version\" 1>&2".getBytes(UTF_8));

    JavacOptions options = createStandardBuilder()
        .setJavacPath(tempPath)
        .build();

    Javac javac = options.getJavac();
    assertTrue(javac instanceof ExternalJavac);

    JavacVersion seen = javac.getVersion();
    assertEquals(seen.toString(), JavacVersion.of("cover-version"), seen);
  }

  @Test
  public void getInputs() {
    Path javacPath = Paths.get("javac");
    FakeSourcePath javacJarPath = new FakeSourcePath("javac_jar");

    JavacOptions options = createStandardBuilder()
        .setJavacPath(javacPath)
        .setJavacJarPath(javacJarPath)
        .build();

    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    assertThat(
        options.getInputs(resolver),
        Matchers.<SourcePath>containsInAnyOrder(javacJarPath));
  }

  private JavacOptions.Builder createStandardBuilder() {
    return JavacOptions.builderForUseInJavaBuckConfig()
        .setSourceLevel("5")
        .setTargetLevel("5");
  }

  private void assertOptionFlags(JavacOptions options, Matcher<Iterable<? super String>> matcher) {
    assertThat(visitOptions(options).flags, matcher);
  }

  private OptionAccumulator visitOptions(JavacOptions options) {
    OptionAccumulator optionsConsumer = new OptionAccumulator();
    options.appendOptionsTo(optionsConsumer, Functions.<Path>identity());
    return optionsConsumer;
  }

  private void assertOptionsHasNoKey(JavacOptions options, String optionKey) {
    assertThat(visitOptions(options).keyVals, not(hasKey(optionKey)));
  }

  private void assertOptionsHasExtra(JavacOptions options, String extra) {
    assertThat(visitOptions(options).extras, hasItem(extra));
  }

  private void assertOptionsHasKeyValue(
      JavacOptions options,
      String optionName,
      String optionValue) {
    assertThat(visitOptions(options).keyVals, hasEntry(optionName, optionValue));
  }
}
