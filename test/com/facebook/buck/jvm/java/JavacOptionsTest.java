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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.AbstractJavacPluginProperties.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;
import org.hamcrest.Matcher;
import org.junit.Test;

public class JavacOptionsTest {

  private final ProjectFilesystem filesystem =
      TestProjectFilesystems.createProjectFilesystem(Paths.get("").toAbsolutePath());

  @Test
  public void buildsAreDebugByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsHasFlag(options, "g");
  }

  @Test
  public void spoolModeToDiskByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertThat(options.getSpoolMode(), is(JavacOptions.SpoolMode.INTERMEDIATE_TO_DISK));
  }

  @Test
  public void productionBuildsCanBeEnabled() {
    JavacOptions options = createStandardBuilder().setProductionBuild(true).build();

    assertOptionsHasNoFlag(options, "g");
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
    JavacPluginParams params =
        JavacPluginParams.builder()
            .setLegacyAnnotationProcessorNames(Collections.singleton("processor"))
            .setProcessOnly(true)
            .build();

    JavacOptions options = createStandardBuilder().setJavaAnnotationProcessorParams(params).build();

    assertOptionsHasFlag(options, "proc:only");
  }

  @Test
  public void shouldAddAllAddedJavacPlugins() {
    JavacPluginProperties props =
        JavacPluginProperties.builder()
            .setType(Type.JAVAC_PLUGIN)
            .setCanReuseClassLoader(true)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .addProcessorNames("ThePlugin")
            .build();

    ResolvedJavacPluginProperties resolvedProps = new ResolvedJavacPluginProperties(props);

    JavacPluginParams params =
        JavacPluginParams.builder().addPluginProperties(resolvedProps).build();

    JavacOptions options = createStandardBuilder().setStandardJavacPluginParams(params).build();

    assertOptionsHasFlag(options, "Xplugin:ThePlugin");
  }

  @Test
  public void shouldNotAddJavacPluginsIfNoSpecified() {
    JavacOptions options = createStandardBuilder().build();
    assertOptionsHasFlagMatching(options, not(hasItem(containsString("Xplugin"))));
  }

  @Test
  public void shouldAddJavacPluginsResolvedClasspathToClasspath() {
    String someMagicJar = "some-magic.jar";
    String alsoJar = "also.jar";

    PathSourcePath someMagicJarPath = FakeSourcePath.of(someMagicJar);
    PathSourcePath alsoJarPath = FakeSourcePath.of(alsoJar);

    JavacPluginProperties props =
        JavacPluginProperties.builder()
            .setType(Type.JAVAC_PLUGIN)
            .setCanReuseClassLoader(true)
            .addAllClasspathEntries(ImmutableList.of(someMagicJarPath, alsoJarPath))
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .addProcessorNames("ThePlugin")
            .build();

    ResolvedJavacPluginProperties resolvedProps = new ResolvedJavacPluginProperties(props);

    JavacPluginParams params =
        JavacPluginParams.builder().addPluginProperties(resolvedProps).build();

    JavacOptions options = createStandardBuilder().setStandardJavacPluginParams(params).build();

    String resolvedSomeMagicPath =
        filesystem.resolve(someMagicJarPath.getRelativePath()).toString();
    String resolvedAlsoPath = filesystem.resolve(alsoJarPath.getRelativePath()).toString();

    assertOptionsHasKeyValue(
        options,
        "processorpath",
        String.format("%s%s%s", resolvedAlsoPath, File.pathSeparator, resolvedSomeMagicPath));
  }

  @Test
  public void shouldAddAllAddedAnnotationProcessors() {
    JavacPluginParams params =
        JavacPluginParams.builder()
            .setLegacyAnnotationProcessorNames(Lists.newArrayList("myproc", "theirproc"))
            .setProcessOnly(true)
            .build();

    JavacOptions options = createStandardBuilder().setJavaAnnotationProcessorParams(params).build();

    assertOptionsHasKeyValue(options, "processor", "myproc,theirproc");
  }

  @Test
  public void shouldDisableAnnotationProcessingIfNoProcessorsSpecified() {
    JavacOptions options = createStandardBuilder().build();
    assertOptionsHasFlag(options, "proc:none");
  }

  @Test
  public void sourceAndTarget7ByDefault() {
    JavacOptions options = createStandardBuilder().build();

    assertOptionsHasKeyValue(options, "source", "7");
    assertOptionsHasKeyValue(options, "target", "7");
  }

  @Test
  public void shouldSetSourceAndTargetLevels() {
    JavacLanguageLevelOptions javacLanguageLevelOptions =
        JavacLanguageLevelOptions.builder().setSourceLevel("8").setTargetLevel("5").build();
    JavacOptions original = createStandardBuilder().build();

    JavacOptions copy =
        JavacOptions.builder(original).setLanguageLevelOptions(javacLanguageLevelOptions).build();
    assertOptionsHasKeyValue(copy, "source", "8");
    assertOptionsHasKeyValue(copy, "target", "5");
  }

  @Test
  public void shouldAddABootClasspathIfTheMapContainsOne() {
    JavacOptions options =
        createStandardBuilder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("5").build())
            .putSourceToBootclasspath(
                "5",
                ImmutableList.of(
                    FakeSourcePath.of("some-magic.jar"), FakeSourcePath.of("also.jar")))
            .build();

    assertOptionsHasKeyValue(
        options, "bootclasspath", String.format("some-magic.jar%salso.jar", File.pathSeparator));
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfOneIsSet() {
    String expectedBootClasspath = String.format("some-magic.jar%salso.jar", File.pathSeparator);
    JavacOptions options =
        createStandardBuilder()
            .setBootclasspath(expectedBootClasspath)
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("5").build())
            .putSourceToBootclasspath(
                "5", ImmutableList.of(FakeSourcePath.of("not-the-right-path.jar")))
            .build();

    assertOptionsHasKeyValue(options, "bootclasspath", expectedBootClasspath);
  }

  @Test
  public void shouldNotOverrideTheBootclasspathIfSourceLevelHasNoMapping() {
    JavacOptions options =
        createStandardBuilder()
            .setBootclasspath("cake.jar")
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("6").build())
            .putSourceToBootclasspath(
                "5",
                ImmutableList.of(
                    FakeSourcePath.of("some-magic.jar"), FakeSourcePath.of("also.jar")))
            .build();

    assertOptionsHasKeyValue(options, "bootclasspath", "cake.jar");
  }

  @Test
  public void shouldCopyMapOfSourceLevelToBootclassPathWhenBuildingNewJavacOptions() {
    JavacOptions original =
        createStandardBuilder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("5").build())
            .putSourceToBootclasspath(
                "5",
                ImmutableList.of(
                    FakeSourcePath.of("some-magic.jar"), FakeSourcePath.of("also.jar")))
            .build();

    JavacOptions copy = JavacOptions.builder(original).build();
    assertOptionsHasKeyValue(
        copy, "bootclasspath", String.format("some-magic.jar%salso.jar", File.pathSeparator));
  }

  @Test
  public void shouldIncoporateExtraOptionsInOutput() {
    JavacOptions options = createStandardBuilder().addExtraArguments("-Xfoobar").build();

    assertOptionsHasExtra(options, "-Xfoobar");
  }

  private JavacOptions.Builder createStandardBuilder() {
    return JavacOptions.builderForUseInJavaBuckConfig();
  }

  private OptionAccumulator visitOptions(JavacOptions options) {
    OptionAccumulator optionsConsumer = new OptionAccumulator();
    options.appendOptionsTo(
        optionsConsumer,
        new AbstractSourcePathResolver() {
          @Override
          protected SourcePath resolveDefaultBuildTargetSourcePath(
              DefaultBuildTargetSourcePath targetSourcePath) {
            throw new UnsupportedOperationException();
          }

          @Override
          public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
            throw new UnsupportedOperationException();
          }

          @Override
          protected ProjectFilesystem getBuildTargetSourcePathFilesystem(
              BuildTargetSourcePath sourcePath) {
            throw new UnsupportedOperationException();
          }
        },
        filesystem);
    return optionsConsumer;
  }

  private void assertOptionsHasNoKey(JavacOptions options, String optionKey) {
    assertThat(visitOptions(options).keyVals, not(hasKey(optionKey)));
  }

  private void assertOptionsHasExtra(JavacOptions options, String extra) {
    assertThat(visitOptions(options).extras, hasItem(extra));
  }

  private void assertOptionsHasFlagMatching(
      JavacOptions options, Matcher<Iterable<? super String>> matcher) {
    assertThat(visitOptions(options).flags, matcher);
  }

  private void assertOptionsHasNoFlag(JavacOptions options, String flag) {
    assertOptionsHasFlagMatching(options, not(hasItem(flag)));
  }

  private void assertOptionsHasFlag(JavacOptions options, String flag) {
    assertOptionsHasFlagMatching(options, hasItem(flag));
  }

  private void assertOptionsHasKeyValue(
      JavacOptions options, String optionName, String optionValue) {
    assertThat(visitOptions(options).keyVals, hasEntry(optionName, optionValue));
  }
}
