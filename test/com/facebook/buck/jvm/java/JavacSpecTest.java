/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JavacSpecTest {
  private ActionGraphBuilder graphBuilder;
  private JavacSpec.Builder specBuilder;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
    specBuilder = JavacSpec.builder();
  }

  @Test
  public void returnsBuiltInJavacByDefault() {
    Javac javac = getJavac();

    assertTrue(javac instanceof JdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsExternalCompilerIfJavacPathPresent() throws IOException {
    // newExecutableFile cannot be executed on windows.
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
    AbsPath externalPath = tmp.newExecutableFile();

    SourcePath javacPath = FakeSourcePath.of(externalPath);
    specBuilder.setJavacPath(javacPath);
    ExternalJavac.ResolvedExternalJavac javac =
        (ExternalJavac.ResolvedExternalJavac)
            getJavac().resolve(graphBuilder.getSourcePathResolver(), tmp.getRoot());

    assertEquals(ImmutableList.of(externalPath.toString()), javac.getCommandPrefix());
  }

  @Test
  public void returnsJarBackedJavacWhenJarPathPresent() {
    SourcePath javacJarPath = FakeSourcePath.of("path/to/javac.jar");

    specBuilder.setJavacJarPath(javacJarPath);
    JarBackedJavac javac = (JarBackedJavac) getJavac();

    assertThat(
        BuildableSupport.deriveInputs(javac).collect(ImmutableList.toImmutableList()),
        Matchers.contains(javacJarPath));
  }

  @Test
  public void customCompilerClassNameIsSet() {
    PathSourcePath javacJarPath = FakeSourcePath.of("javac_jar");
    String compilerClassName = "test.compiler";
    specBuilder.setJavacJarPath(javacJarPath).setCompilerClassName(compilerClassName);

    JarBackedJavac.ResolvedJarBackedJavac javac =
        (JarBackedJavac.ResolvedJarBackedJavac)
            getJavac().resolve(graphBuilder.getSourcePathResolver(), tmp.getRoot());

    assertEquals(compilerClassName, javac.getCompilerClassName());
  }

  @Test(expected = HumanReadableException.class)
  public void mayOnlyPassOneOfJavacOrJavacJar() {
    PathSourcePath sourcePath = FakeSourcePath.of("path");
    specBuilder.setJavacPath(sourcePath).setJavacJarPath(sourcePath);

    getJavac();
  }

  private Javac getJavac() {
    return specBuilder.build().getJavacProvider().resolve(graphBuilder);
  }
}
