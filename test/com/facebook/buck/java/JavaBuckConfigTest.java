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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuckConfigTestUtils;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
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
  public void whenJavacIsNotSetThenAbsentIsReturned() throws IOException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));
    assertThat(config.getJavacPath(), is(equalTo(Optional.<Path>absent())));
  }

  @Test
  public void whenJavacExistsAndIsExecutableThenCorrectPathIsReturned() throws IOException {
    File javac = temporaryFolder.newFile();
    assertTrue(javac.setExecutable(true));

    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[tools]",
            "    javac = " + javac.toPath().toString().replace("\\", "\\\\")));
    JavaBuckConfig config = createWithDefaultFilesystem(reader);

    assertThat(config.getJavacPath(), is(equalTo(Optional.of(javac.toPath()))));
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
      assertThat(
          e.getHumanReadableErrorMessage(),
          is(equalTo("Javac does not exist: " + invalidPath)));
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
      assertThat(
          e.getHumanReadableErrorMessage(),
          is(equalTo("Javac is not executable: " + javac.getPath())));
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
      assertThat(e.getHumanReadableErrorMessage(),
          is(equalTo("Overridden tools:javac_jar path not found: " + invalidPath)));
    }
  }

  @Test
  public void whenJavaBinIsSetReturnAsOverride() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[java]",
        "    java_bin = /usr/bin/my_java_wrapper.sh"));

    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    Optional<String> javaBinOverride = config.getJavaBinOverride();

    assertTrue(javaBinOverride.isPresent());
    assertThat(javaBinOverride.get(), is(equalTo("/usr/bin/my_java_wrapper.sh")));
  }

  @Test
  public void whenJavaBinIsNotSetReturnEmpty() throws IOException {
    Reader reader = new StringReader("");

    JavaBuckConfig config = createWithDefaultFilesystem(reader);
    Optional<String> javaBinOverride = config.getJavaBinOverride();

    assertFalse(javaBinOverride.isPresent());
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

    assertThat(options.getSourceLevel(), is(equalTo(sourceLevel)));
    assertThat(options.getTargetLevel(), is(equalTo(targetLevel)));
  }

  @Test
  public void shouldSetJavaTargetAndSourceVersionDefaultToSaneValues()
      throws IOException, InterruptedException {
    JavaBuckConfig config = createWithDefaultFilesystem(new StringReader(""));

    JavacOptions options = config.getDefaultJavacOptions();

    assertThat(options.getSourceLevel(), is(equalTo(TARGETED_JAVA_VERSION)));
    assertThat(options.getTargetLevel(), is(equalTo(TARGETED_JAVA_VERSION)));
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
