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


import static com.facebook.buck.jvm.java.JavaBuckConfig.TARGETED_JAVA_VERSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuckConfigTestUtils;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;

public class JavaBuckConfigTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();
  private ProjectFilesystem defaultFilesystem;

  @Before
  public void setUpDefaultFilesystem() {
    defaultFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
  }

  @Test
  public void whenJavaIsNotSetThenJavaFromPathIsReturned() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    JavaOptions options = config.getDefaultJavaOptions();
    assertEquals(Optional.absent(), options.getJavaPath());
    assertEquals("java", options.getJavaRuntimeLauncher().getCommand());
  }

  @Test
  public void whenJavaExistsAndIsExecutableThenItIsReturned() throws IOException {
    File java = temporaryFolder.newExecutableFile();
    String javaCommand = java.toPath().toString();
    JavaBuckConfig config = new JavaBuckConfig(
        FakeBuckConfig
            .builder()
            .setSections(ImmutableMap.of("tools", ImmutableMap.of("java", javaCommand)))
            .build());

    JavaOptions options = config.getDefaultJavaOptions();
    assertEquals(Optional.of(java.toPath()), options.getJavaPath());
    assertEquals(javaCommand, options.getJavaRuntimeLauncher().getCommand());
  }

  @Test
  public void whenJavacIsNotSetThenAbsentIsReturned() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    assertEquals(Optional.absent(), config.getJavacPath());
  }

  @Test
  public void whenJavacExistsAndIsExecutableThenCorrectPathIsReturned() throws IOException {
    File javac = temporaryFolder.newExecutableFile();

    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[tools]",
            "    javac = " + javac.toPath().toString().replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);

    assertEquals(Optional.of(javac.toPath()), config.getJavacPath());
  }

  @Test
  public void whenJavacDoesNotExistThenHumanReadableExceptionIsThrown() throws IOException {
    String invalidPath = temporaryFolder.getRoot().getAbsolutePath() + "DoesNotExist";
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[tools]",
        "    javac = " + invalidPath.replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacPath();
      fail("Should throw exception as javac file does not exist.");
    } catch (HumanReadableException e) {
      assertEquals(e.getHumanReadableErrorMessage(), "javac does not exist: " + invalidPath);
    }
  }

  @Test
  public void whenJavacIsNotExecutableThenHumanReadableExeceptionIsThrown() throws IOException {
    File javac = temporaryFolder.newFile();
    assumeTrue("Should be able to set file non-executable", javac.setExecutable(false));

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[tools]",
        "    javac = " + javac.toPath().toString()));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacPath();
      fail("Should throw exception as javac file is not executable.");
    } catch (HumanReadableException e) {
      assertEquals(e.getHumanReadableErrorMessage(), "javac is not executable: " + javac.getPath());
    }
  }

  @Test
  public void whenJavacJarDoesNotExistThenHumanReadableExceptionIsThrown() throws IOException {
    String invalidPath = temporaryFolder.getRoot().getAbsolutePath() + "DoesNotExist";
    Reader reader = new StringReader(Joiner.on('\n').join(
            "[tools]",
            "    javac_jar = " + invalidPath.replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacJarPath();
      fail("Should throw exception as javac file does not exist.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Overridden tools:javac_jar path not found: " + invalidPath,
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void shouldSetJavaTargetAndSourceVersionFromConfig()
      throws IOException, InterruptedException {
    String sourceLevel = "source-level";
    String targetLevel = "target-level";

    String localConfig = String.format(
        "[java]\nsource_level = %s\ntarget_level = %s",
        sourceLevel,
        targetLevel);

    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(localConfig));

    JavacOptions options = config.getDefaultJavacOptions();

    assertEquals(sourceLevel, options.getSourceLevel());
    assertEquals(targetLevel, options.getTargetLevel());
  }

  @Test
  public void shouldSetJavaTargetAndSourceVersionDefaultToSaneValues()
      throws IOException, InterruptedException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));

    JavacOptions options = config.getDefaultJavacOptions();

    assertEquals(TARGETED_JAVA_VERSION, options.getSourceLevel());
    assertEquals(TARGETED_JAVA_VERSION, options.getTargetLevel());
  }

  @Test
  public void shouldPopulateTheMapOfSourceLevelToBootclasspath()
      throws IOException, InterruptedException {
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
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    JavaBuckConfig javaConfig = new JavaBuckConfig(buckConfig);
    JavacOptions javacOptions = javaConfig.getDefaultJavacOptions();

    Javac javac = javacOptions.getJavac();
    assertTrue(javac.getClass().toString(), javac instanceof Jsr199Javac);
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJavacSet()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    JavaBuckConfig javaConfig = new JavaBuckConfig(buckConfig);
    JavacOptions javacOptions = javaConfig.getDefaultJavacOptions();

    assertEquals(
        javac.toPath(),
        ((ExternalJavac) javacOptions.getJavac()).getPath());
  }

  @Test
  public void classUsageTracking()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    String jarPath = temporaryFolder.newFile("javac.jar").toString();
    String config = Joiner.on('\n').join(
        "[tools]",
        "    javac_jar = " + jarPath.replace("\\", "\\\\"));

    assertTrue(createWithDefaultFilesystem(
        new StringReader(config))
        .getDefaultJavacOptions()
        .trackClassUsage());

    assertFalse(createWithDefaultFilesystem(
        new StringReader(config + "\n[java]\ntrack_class_usage = false"))
        .getDefaultJavacOptions()
        .trackClassUsage());
  }

  private void assertOptionKeyAbsent(JavacOptions options, String key) {
    OptionAccumulator optionsConsumer = visitOptions(options);
    assertThat(optionsConsumer.keyVals, not(hasKey(key)));
  }

  private void assertOptionsContains(
      JavacOptions options,
      String key,
      String value) {
    OptionAccumulator optionsConsumer = visitOptions(options);
    assertThat(optionsConsumer.keyVals, hasEntry(key, value));
  }

  private OptionAccumulator visitOptions(JavacOptions options) {
    OptionAccumulator optionsConsumer = new OptionAccumulator();
    options.appendOptionsTo(optionsConsumer, Functions.<Path>identity());
    return optionsConsumer;
  }

  private JavaBuckConfig createWithDefaultFilesystem(Reader reader)
      throws IOException {
    BuckConfig raw = BuckConfigTestUtils.createFromReader(
        reader,
        defaultFilesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
    return new JavaBuckConfig(raw);
  }
}
