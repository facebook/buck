/*
 * Copyright 2014-present Facebook, Inc.
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

import static com.facebook.buck.jvm.java.JavacOptions.TARGETED_JAVA_VERSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JavaBuckConfigTest {

  public static final BuildRuleResolver RULE_RESOLVER = new TestActionGraphBuilder();
  private static final SourcePathResolver PATH_RESOLVER =
      DefaultSourcePathResolver.from(new SourcePathRuleFinder(RULE_RESOLVER));

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private ProjectFilesystem defaultFilesystem;

  @Before
  public void setUpDefaultFilesystem() {
    defaultFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
  }

  @Test
  public void whenJavaIsNotSetThenJavaFromPathIsReturned() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    JavaOptions javaOptions = config.getDefaultJavaOptions();
    assertEquals(
        ImmutableList.of("java"),
        javaOptions.getJavaRuntimeLauncher(RULE_RESOLVER).getCommandPrefix(PATH_RESOLVER));

    JavaOptions javaForTestsOptions = config.getDefaultJavaOptionsForTests();
    assertEquals(
        ImmutableList.of("java"),
        javaForTestsOptions.getJavaRuntimeLauncher(RULE_RESOLVER).getCommandPrefix(PATH_RESOLVER));
  }

  @Test
  public void whenJavaExistsAndIsExecutableThenItIsReturned() throws IOException {
    Path java = temporaryFolder.newExecutableFile();
    Path javaForTests = temporaryFolder.newExecutableFile();
    String javaCommand = java.toString();
    String javaForTestsCommand = javaForTests.toString();
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(defaultFilesystem)
            .setSections(
                ImmutableMap.of(
                    "tools",
                    ImmutableMap.of(
                        "java", javaCommand,
                        "java_for_tests", javaForTestsCommand)))
            .build()
            .getView(JavaBuckConfig.class);

    JavaOptions javaOptions = config.getDefaultJavaOptions();
    assertEquals(
        ImmutableList.of(javaCommand),
        javaOptions.getJavaRuntimeLauncher(RULE_RESOLVER).getCommandPrefix(PATH_RESOLVER));

    JavaOptions javaForTestsOptions = config.getDefaultJavaOptionsForTests();
    assertEquals(
        ImmutableList.of(javaForTestsCommand),
        javaForTestsOptions.getJavaRuntimeLauncher(RULE_RESOLVER).getCommandPrefix(PATH_RESOLVER));
  }

  @Test
  public void whenJavaExistsAndIsRelativePathThenItsAbsolutePathIsReturned() throws IOException {
    Path java = temporaryFolder.newExecutableFile();
    String javaFilename = java.getFileName().toString();
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(defaultFilesystem)
            .setSections(ImmutableMap.of("tools", ImmutableMap.of("java", javaFilename)))
            .build()
            .getView(JavaBuckConfig.class);

    JavaOptions options = config.getDefaultJavaOptions();
    assertEquals(
        ImmutableList.of(java.toString()),
        options.getJavaRuntimeLauncher(RULE_RESOLVER).getCommandPrefix(PATH_RESOLVER));
  }

  @Test
  public void whenJavaForTestsIsNotSetThenJavaIsReturned() throws IOException {
    Path java = temporaryFolder.newExecutableFile();
    String javaCommand = java.toString();
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(defaultFilesystem)
            .setSections(ImmutableMap.of("tools", ImmutableMap.of("java", javaCommand)))
            .build()
            .getView(JavaBuckConfig.class);

    JavaOptions options = config.getDefaultJavaOptionsForTests();
    assertEquals(
        ImmutableList.of(javaCommand),
        options.getJavaRuntimeLauncher(RULE_RESOLVER).getCommandPrefix(PATH_RESOLVER));
  }

  @Test
  public void whenJavacIsNotSetThenAbsentIsReturned() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    assertEquals(Optional.empty(), config.getJavacPath());
  }

  @Test
  public void whenJavacExistsAndIsExecutableThenCorrectPathIsReturned() throws IOException {
    Path javac = temporaryFolder.newExecutableFile();

    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join("[tools]", "    javac = " + javac.toString().replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);

    assertEquals(config.getDelegate().getPathSourcePath(javac), config.getJavacPath().get());
  }

  @Test
  public void whenJavacIsABuildTargetThenCorrectPathIsReturned() throws IOException {
    BuildTarget javacTarget = BuildTargetFactory.newInstance(defaultFilesystem, "//:javac");
    Reader reader =
        new StringReader(
            Joiner.on('\n').join("[tools]", "    javac = " + javacTarget.getFullyQualifiedName()));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    assertEquals(DefaultBuildTargetSourcePath.of(javacTarget), config.getJavacPath().get());
  }

  @Test
  public void whenJavacDoesNotExistThenHumanReadableExceptionIsThrown() throws IOException {
    String invalidPath = temporaryFolder.getRoot().toAbsolutePath() + "DoesNotExist";
    Reader reader =
        new StringReader(
            Joiner.on('\n').join("[tools]", "    javac = " + invalidPath.replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacPath();
      fail("Should throw exception as javac file does not exist.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Overridden tools:javac path not found: " + invalidPath,
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void whenJavacIsNotExecutableThenHumanReadableExeceptionIsThrown() throws IOException {
    assumeThat(
        "Files on Windows are executable by default.",
        Platform.detect(),
        is(not(Platform.WINDOWS)));
    Path javac = temporaryFolder.newFile();

    Reader reader = new StringReader(Joiner.on('\n').join("[tools]", "    javac = " + javac));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config
          .getJavacSpec()
          .getJavacProvider()
          .resolve(new SourcePathRuleFinder(new TestActionGraphBuilder()));
      fail("Should throw exception as javac file is not executable.");
    } catch (HumanReadableException e) {
      assertEquals(e.getHumanReadableErrorMessage(), "javac is not executable: " + javac);
    }
  }

  @Test
  public void whenJavacJarDoesNotExistThenHumanReadableExceptionIsThrown() throws IOException {
    String invalidPath = temporaryFolder.getRoot().toAbsolutePath() + "DoesNotExist";
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join("[tools]", "    javac_jar = " + invalidPath.replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacSpec().getJavacJarPath();
      fail("Should throw exception as javac file does not exist.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Overridden tools:javac_jar path not found: " + invalidPath,
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void shouldSetJavaTargetAndSourceVersionFromConfig() throws IOException {
    String sourceLevel = "source-level";
    String targetLevel = "target-level";

    String localConfig =
        String.format("[java]\nsource_level = %s\ntarget_level = %s", sourceLevel, targetLevel);

    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(localConfig));

    JavacOptions options = config.getDefaultJavacOptions();

    assertEquals(sourceLevel, options.getSourceLevel());
    assertEquals(targetLevel, options.getTargetLevel());
  }

  @Test
  public void shouldSetJavaTargetAndSourceVersionDefaultToSaneValues() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));

    JavacOptions options = config.getDefaultJavacOptions();

    assertEquals(TARGETED_JAVA_VERSION, options.getSourceLevel());
    assertEquals(TARGETED_JAVA_VERSION, options.getTargetLevel());
  }

  @Test
  public void shouldPopulateTheMapOfSourceLevelToBootclasspath() throws IOException {
    String localConfig = "[java]\nbootclasspath-6 = one.jar\nbootclasspath-7 = two.jar";
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(localConfig));

    JavacOptions options = config.getDefaultJavacOptions();

    JavacOptions jse5 = JavacOptions.builder(options).setSourceLevel("5").build();
    JavacOptions jse6 = JavacOptions.builder(options).setSourceLevel("6").build();
    JavacOptions jse7 = JavacOptions.builder(options).setSourceLevel("7").build();

    assertOptionKeyAbsent(jse5, "bootclasspath");
    assertOptionsContains(jse6, "bootclasspath", "one.jar");
    assertOptionsContains(jse7, "bootclasspath", "two.jar");
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJsr199Javac()
      throws NoSuchBuildTargetException {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    JavaBuckConfig javaConfig = buckConfig.getView(JavaBuckConfig.class);

    Javac javac = JavacFactoryHelper.createJavacFactory(javaConfig).create(null, null);
    assertTrue(javac.getClass().toString(), javac instanceof Jsr199Javac);
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJavacSet()
      throws IOException, NoSuchBuildTargetException {
    final String javac = temporaryFolder.newExecutableFile().toString();

    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("tools", ImmutableMap.of("javac", javac));
    BuckConfig buckConfig =
        FakeBuckConfig.builder().setFilesystem(defaultFilesystem).setSections(sections).build();
    JavaBuckConfig javaConfig = buckConfig.getView(JavaBuckConfig.class);

    assertEquals(
        javac, JavacFactoryHelper.createJavacFactory(javaConfig).create(null, null).getShortName());
  }

  @Test
  public void trackClassUsageCanBeDisabled() {
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("java", ImmutableMap.of("track_class_usage", "false")))
            .build()
            .getView(JavaBuckConfig.class);

    assumeThat(config.getJavacSpec().getJavacSource(), is(Javac.Source.JDK));
    assertFalse(config.trackClassUsage());
  }

  @Test
  public void desugarInterfaceMethodsCanBeEnabled() {
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("java", ImmutableMap.of("desugar_interface_methods", "true")))
            .build()
            .getView(JavaBuckConfig.class);

    assertTrue(config.shouldDesugarInterfaceMethods());
  }

  @Test
  public void doNotTrackClassUsageByDefaultForExternJavac() throws IOException {
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(defaultFilesystem)
            .setSections(
                ImmutableMap.of(
                    "tools",
                    ImmutableMap.of("javac", temporaryFolder.newExecutableFile().toString())))
            .build()
            .getView(JavaBuckConfig.class);

    assumeThat(config.getJavacSpec().getJavacSource(), is(Javac.Source.EXTERNAL));

    assertFalse(config.trackClassUsage());
  }

  @Test
  public void doNotTrackClassUsageEvenIfAskedForExternJavac() throws IOException {
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(defaultFilesystem)
            .setSections(
                ImmutableMap.of(
                    "tools",
                    ImmutableMap.of("javac", temporaryFolder.newExecutableFile().toString()),
                    "java",
                    ImmutableMap.of("track_class_usage", "true")))
            .build()
            .getView(JavaBuckConfig.class);

    assumeThat(config.getJavacSpec().getJavacSource(), is(Javac.Source.EXTERNAL));
    assertFalse(config.trackClassUsage());
  }

  @Test
  public void trackClassUsageByDefaultForJavacFromJar() throws IOException {
    JavaBuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(defaultFilesystem)
            .setSections(
                ImmutableMap.of(
                    "tools",
                    ImmutableMap.of("javac_jar", temporaryFolder.newExecutableFile().toString())))
            .build()
            .getView(JavaBuckConfig.class);

    assumeThat(config.getJavacSpec().getJavacSource(), is(Javac.Source.JAR));

    assertTrue(config.trackClassUsage());
  }

  @Test
  public void trackClassUsageByDefaultForJavacFromJDK() {
    JavaBuckConfig config = FakeBuckConfig.builder().build().getView(JavaBuckConfig.class);

    assumeThat(config.getJavacSpec().getJavacSource(), is(Javac.Source.JDK));

    assertTrue(config.trackClassUsage());
  }

  @Test
  public void testCompileFullJarsByDefault() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    assertThat(config.getAbiGenerationMode(), Matchers.equalTo(AbiGenerationMode.CLASS));
  }

  private void assertOptionKeyAbsent(JavacOptions options, String key) {
    OptionAccumulator optionsConsumer = visitOptions(options);
    assertThat(optionsConsumer.keyVals, not(hasKey(key)));
  }

  private void assertOptionsContains(JavacOptions options, String key, String value) {
    OptionAccumulator optionsConsumer = visitOptions(options);
    assertThat(optionsConsumer.keyVals, hasEntry(key, value));
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
        defaultFilesystem);
    return optionsConsumer;
  }

  private JavaBuckConfig createWithDefaultFilesystem(Reader reader) throws IOException {
    BuckConfig raw =
        BuckConfigTestUtils.createFromReader(
            reader,
            defaultFilesystem,
            Architecture.detect(),
            Platform.detect(),
            EnvVariablesProvider.getSystemEnv());
    return raw.getView(JavaBuckConfig.class);
  }
}
