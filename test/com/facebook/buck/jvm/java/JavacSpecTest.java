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

import static com.facebook.buck.jvm.java.BuiltInJavac.DEFAULT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class JavacSpecTest {
  private BuildRuleResolver ruleResolver;
  private SourcePathRuleFinder ruleFinder;
  private JavacSpec.Builder specBuilder;

  @Before
  public void setUp() {
    ruleResolver = new TestBuildRuleResolver();
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    specBuilder = JavacSpec.builder();
  }

  @Test
  public void returnsBuiltInJavacByDefault() {
    Javac javac = getJavac();

    assertTrue(javac instanceof JdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsBuiltInJavacWhenCompilerArgHasDefault() {
    Either<BuiltInJavac, SourcePath> either = Either.ofLeft(DEFAULT);
    specBuilder.setCompiler(either);

    Javac javac = getJavac();
    assertTrue(javac instanceof JdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsExternalCompilerIfJavacPathPresent() {
    Path externalPath = Paths.get("/foo/bar/path/to/javac");
    SourcePath javacPath = FakeSourcePath.of(externalPath);

    specBuilder.setJavacPath(Either.ofRight(javacPath));
    ExternalJavac javac = (ExternalJavac) getJavac();
    assertTrue(javac.getActualPath().isLeft());
    assertEquals(externalPath, javac.getActualPath().getLeft());
  }

  @Test
  public void returnsExternalCompilerIfCompilerArgHasPath() {
    Path externalJavac = Paths.get("/foo/bar/javac.exe").toAbsolutePath();
    SourcePath sourcePath = FakeSourcePath.of(externalJavac.toString());
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    specBuilder.setCompiler(either);
    ExternalJavac javac = (ExternalJavac) getJavac();

    assertTrue(javac.getActualPath().isLeft());
    assertEquals(externalJavac, javac.getActualPath().getLeft());
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacPathArg() {
    Path externalJavacPath = Paths.get("/foo/bar/javac.exe").toAbsolutePath();
    SourcePath sourcePath = FakeSourcePath.of(externalJavacPath.toString());
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    specBuilder.setCompiler(either).setJavacPath(Either.ofLeft(Paths.get("does-not-exist")));
    ExternalJavac javac = (ExternalJavac) getJavac();

    assertTrue(javac.getActualPath().isLeft());
    assertEquals(externalJavacPath, javac.getActualPath().getLeft());
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
  public void returnsJarBackedJavacWhenCompilerArgIsPrebuiltJar() {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJar prebuiltJar =
        PrebuiltJarBuilder.createBuilder(target).setBinaryJar(javacJarPath).build(ruleResolver);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    specBuilder.setCompiler(either);
    JarBackedJavac javac = (JarBackedJavac) getJavac();

    assertThat(
        BuildableSupport.deriveInputs(javac).collect(ImmutableList.toImmutableList()),
        Matchers.contains(prebuiltJar.getSourcePathToOutput()));
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacJarArg() {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJar prebuiltJar =
        PrebuiltJarBuilder.createBuilder(target).setBinaryJar(javacJarPath).build(ruleResolver);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    specBuilder
        .setCompiler(either)
        .setJavacJarPath(
            PathSourcePath.of(new FakeProjectFilesystem(), Paths.get("does-not-exist")));
    JarBackedJavac javac = (JarBackedJavac) getJavac();

    assertThat(
        BuildableSupport.deriveInputs(javac).collect(ImmutableList.toImmutableList()),
        Matchers.contains(prebuiltJar.getSourcePathToOutput()));
  }

  @Test
  public void customCompilerClassNameIsSet() {
    PathSourcePath javacJarPath = FakeSourcePath.of("javac_jar");
    String compilerClassName = "test.compiler";
    specBuilder.setJavacJarPath(javacJarPath).setCompilerClassName(compilerClassName);

    JarBackedJavac javac = (JarBackedJavac) getJavac();

    assertEquals(compilerClassName, javac.getCompilerClassName());
  }

  @Test(expected = HumanReadableException.class)
  public void mayOnlyPassOneOfJavacOrJavacJar() {
    PathSourcePath sourcePath = FakeSourcePath.of("path");
    specBuilder.setJavacPath(Either.ofRight(sourcePath)).setJavacJarPath(sourcePath);

    getJavac();
  }

  private Javac getJavac() {
    return specBuilder.build().getJavacProvider().resolve(ruleFinder);
  }
}
