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

package com.facebook.buck.java;


import static com.facebook.buck.java.JavaBuckConfig.TARGETED_JAVA_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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

  @Test
  public void whenJavacIsNotSetThenAbsentIsReturned() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    assertEquals(Optional.absent(), config.getJavacPath());
  }

  @Test
  public void whenJavacExistsAndIsExecutableThenCorrectPathIsReturned() throws IOException {
    File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[tools]",
            "    javac = " + javac.toPath().toString()));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);

    assertEquals(Optional.of(javac.toPath()), config.getJavacPath());
  }

  @Test
  public void whenJavacDoesNotExistThenHumanReadableExceptionIsThrown() throws IOException {
    String invalidPath = temporaryFolder.getRoot().getAbsolutePath() + "DoesNotExist";
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[tools]",
        "    javac = " + invalidPath));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacPath();
      fail("Should throw exception as javac file does not exist.");
    } catch (HumanReadableException e) {
      assertEquals(e.getHumanReadableErrorMessage(), "Javac does not exist: " + invalidPath);
    }
  }

  @Test
  public void whenJavacIsNotExecutableThenHumanReadableExeceptionIsThrown() throws IOException {
    File javac = temporaryFolder.newFile();
    javac.setExecutable(false);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[tools]",
        "    javac = " + javac.toPath().toString()));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    try {
      config.getJavacPath();
      fail("Should throw exception as javac file is not executable.");
    } catch (HumanReadableException e) {
      assertEquals(e.getHumanReadableErrorMessage(), "Javac is not executable: " + javac.getPath());
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

    JavacOptions options = config.getDefaultJavacOptions(
        new ProcessExecutor(new TestConsole()));

    assertEquals(sourceLevel, options.getSourceLevel());
    assertEquals(targetLevel, options.getTargetLevel());
  }

  @Test
  public void shouldSetJavaTargetAndSourceVersionDefaultToSaneValues()
      throws IOException, InterruptedException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));

    JavacOptions options = config.getDefaultJavacOptions(
        new ProcessExecutor(new TestConsole()));

    assertEquals(TARGETED_JAVA_VERSION, options.getSourceLevel());
    assertEquals(TARGETED_JAVA_VERSION, options.getTargetLevel());
  }

  @Test
  public void shouldPopulateTheMapOfSourceLevelToBootclasspath()
      throws IOException, InterruptedException {
    String localConfig = "[java]\nbootclasspath-6 = one.jar\nbootclasspath-7 = two.jar";
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(localConfig));

    JavacOptions options = config.getDefaultJavacOptions(new ProcessExecutor(new TestConsole()));

    JavacOptions jse5 = JavacOptions.builder(options).setSourceLevel("5").build();
    JavacOptions jse6 = JavacOptions.builder(options).setSourceLevel("6").build();
    JavacOptions jse7 = JavacOptions.builder(options).setSourceLevel("7").build();

    assertFalse(isOptionContaining(jse5, "-bootclasspath"));
    assertTrue(isOptionContaining(jse6, "-bootclasspath one.jar"));
    assertTrue(isOptionContaining(jse7, "-bootclasspath two.jar"));
  }

  private boolean isOptionContaining(JavacOptions options, String expectedParameter) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    options.appendOptionsToList(builder, Functions.<Path>identity());
    String joined = Joiner.on(" ").join(builder.build());

    return joined.contains(expectedParameter);
  }

  @Test
  public void shouldDefaultToCreatingAnInMemoryJavac() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));

    Javac actualJavac = config.getJavac();
    assertTrue(actualJavac instanceof Jsr199Javac);
  }

  @Test
  public void shouldCreateAnExternalJavaWhenTheJavacPathIsSet() throws IOException {
    File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    String localConfig = "[tools]\njavac = " + javac;
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(localConfig));

    Javac actualJavac = config.getJavac();
    assertTrue(actualJavac instanceof ExternalJavac);
  }

  private JavaBuckConfig createWithDefaultFilesystem(Reader reader)
      throws IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    BuildTargetParser parser = new BuildTargetParser();
    BuckConfig raw = BuckConfig.createFromReader(
        reader,
        filesystem,
        parser,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
    return new JavaBuckConfig(raw);
  }
}
