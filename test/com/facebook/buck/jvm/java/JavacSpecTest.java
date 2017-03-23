/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class JavacSpecTest {
  private BuildRuleResolver ruleResolver;
  private SourcePathRuleFinder ruleFinder;
  private JavacSpec.Builder specBuilder;

  @Before
  public void setUp() {
    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    specBuilder = JavacSpec.builder();
  }

  @Test
  public void returnsBuiltInJavacByDefault() {
    Javac javac = getJavac();

    assertTrue(javac instanceof JdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsOutOfProcJavacIfRequested() {
    specBuilder.setJavacLocation(Javac.Location.OUT_OF_PROCESS);
    Javac javac = getJavac();

    assertTrue(javac instanceof OutOfProcessJdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsExternalCompilerIfJavacPathPresent() throws IOException {
    SourcePath javacPath = new FakeSourcePath("path/to/javac");

    specBuilder.setJavacPath(Either.ofRight(javacPath));
    ExternalJavac javac = (ExternalJavac) getJavac();

    assertThat(
        javac.getInputs(),
        Matchers.contains(javacPath));
  }

  @Test
  public void returnsJarBackedJavacWhenJarPathPresent() throws IOException {
    SourcePath javacJarPath = new FakeSourcePath("path/to/javac.jar");

    specBuilder.setJavacJarPath(javacJarPath);
    JarBackedJavac javac = (JarBackedJavac) getJavac();

    assertThat(
        javac.getInputs(),
        Matchers.contains(javacJarPath));
  }

  @Test
  public void returnsOutOfProcessJarBackedJavacIfRequested() throws IOException {
    SourcePath javacJarPath = new FakeSourcePath("path/to/javac.jar");

    specBuilder
        .setJavacJarPath(javacJarPath)
        .setJavacLocation(Javac.Location.OUT_OF_PROCESS);
    OutOfProcessJarBackedJavac javac = (OutOfProcessJarBackedJavac) getJavac();

    assertThat(
        javac.getInputs(),
        Matchers.contains(javacJarPath));
  }

  @Test
  public void customCompilerClassNameIsSet()
      throws IOException {
    FakeSourcePath javacJarPath = new FakeSourcePath("javac_jar");
    String compilerClassName = "test.compiler";
    specBuilder
        .setJavacJarPath(javacJarPath)
        .setCompilerClassName(compilerClassName);

    JarBackedJavac javac = (JarBackedJavac) getJavac();

    assertEquals(compilerClassName, javac.getCompilerClassName());
  }

  private Javac getJavac() {
    return specBuilder.build().getJavacProvider().resolve(ruleFinder);
  }
}
