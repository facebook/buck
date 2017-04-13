/*
 * Copyright 2015-present Facebook, Inc.
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
import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Optional;

public class JvmLibraryArgInterpreterTest {
  private JavacOptions defaults;
  private JvmLibraryArg arg;
  private BuildRuleResolver ruleResolver;

  @Before
  public void createHelpers() {
    defaults = JavacOptions.builder()
        .setSourceLevel("8")
        .setTargetLevel("8")
        .build();

    arg = new JvmLibraryArg();
    populateWithDefaultValues(arg);

    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
  }

  @Test
  public void javaVersionSetsBothSourceAndTargetLevels() {
    arg.source = Optional.empty();
    arg.target = Optional.empty();
    arg.javaVersion = Optional.of("1.4");  // Set in the past, so if we ever bump the default....

    JavacOptions options = createJavacOptions(arg);

    assertEquals("1.4", options.getSourceLevel());
    assertEquals("1.4", options.getTargetLevel());
  }

  @Test
  public void settingJavaVersionAndSourceLevelIsAnError() {
    arg.source = Optional.of("1.4");
    arg.target = Optional.empty();
    arg.javaVersion = Optional.of("1.4");

    try {
      createJavacOptions(arg);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }

  @Test
  public void settingJavaVersionAndTargetLevelIsAnError() {
    arg.source = Optional.empty();
    arg.target = Optional.of("1.4");
    arg.javaVersion = Optional.of("1.4");

    try {
      createJavacOptions(arg);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }


  @Test
  public void compilerArgIsSet() {
    Either<BuiltInJavac, SourcePath> either = Either.ofLeft(DEFAULT);
    arg.compiler = Optional.of(either);

    assertEquals(
        arg.compiler,
        arg.getJavacSpec().getCompiler());
  }

  @Test
  public void javacArgIsSet() {
    arg.javac = Optional.of(Paths.get("does-not-exist"));

    assertEquals(
        Optional.of(Either.ofLeft(arg.javac.get())),
        arg.getJavacSpec().getJavacPath());
  }

  @Test
  public void testJavacJarArgIsSet() {
    arg.javacJar = Optional.of(new FakeSourcePath("does-not-exist"));

    assertEquals(
        arg.javacJar,
        arg.getJavacSpec().getJavacJarPath());
  }

  @Test
  public void testCompilerClassNameArgIsSet() {
    arg.javacJar = Optional.of(new FakeSourcePath("does-not-exist"));
    arg.compilerClassName = Optional.of("compiler");

    assertEquals(
        arg.compilerClassName,
        arg.getJavacSpec().getCompilerClassName());
  }

  @Test
  public void testNoJavacSpecIfNoJavacArg() {
    assertNull(arg.getJavacSpec());
  }

  @Test
  public void sourceAbiGenerationCanBeDisabledPerTarget() {
    arg.generateAbiFromSource = Optional.of(false);
    defaults = defaults.withCompilationMode(Javac.CompilationMode.FULL_ENFORCING_REFERENCES);

    JavacOptions options = createJavacOptions(arg);

    assertEquals(options.getCompilationMode(), Javac.CompilationMode.FULL);
  }

  @Test
  public void sourceAbiGenerationCannotBeEnabledPerTargetIfTheFeatureIsDisabled() {
    assertEquals(defaults.getCompilationMode(), Javac.CompilationMode.FULL);

    arg.generateAbiFromSource = Optional.of(true);

    JavacOptions options = createJavacOptions(arg);

    assertEquals(options.getCompilationMode(), Javac.CompilationMode.FULL);
  }

  private JavacOptions createJavacOptions(JvmLibraryArg arg) {
    return JavacOptionsFactory.create(
        defaults,
        new FakeBuildRuleParamsBuilder("//not:real").build(),
        ruleResolver,
        arg);
  }

  private void populateWithDefaultValues(Object arg) {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    try {
      new ConstructorArgMarshaller(
          new DefaultTypeCoercerFactory()).populate(
          createCellRoots(filesystem),
          filesystem,
          BuildTargetFactory.newInstance("//example:target"),
          arg,
          ImmutableSet.builder(),
          ImmutableSet.builder(),
          ImmutableSet.builder(),
          ImmutableMap.of());
    } catch (ParamInfoException error) {
      Throwables.throwIfUnchecked(error);
    }
  }
}
