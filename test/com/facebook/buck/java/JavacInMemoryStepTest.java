/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.java.JavaCompilerEnvironment.TARGETED_JAVA_VERSION;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JavacInMemoryStepTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");

  @Test
  public void testFindFailedImports() throws Exception {
    String lineSeperator = System.getProperty("line.separator");

    String stderrOutput = Joiner.on(lineSeperator).join(ImmutableList.of(
        "java/com/foo/bar.java:5: package javax.annotation.concurrent does not exist",
        "java/com/foo/bar.java:99: error: cannot access com.facebook.Raz",
        "java/com/foo/bar.java:142: cannot find symbol: class ImmutableSet",
        "java/com/foo/bar.java:999: you are a clown"));

    ImmutableSet<String> missingImports =
        JavacInMemoryStep.findFailedImports(stderrOutput);
    assertEquals(
        ImmutableSet.of("javax.annotation.concurrent", "com.facebook.Raz", "ImmutableSet"),
        missingImports);
  }

  @Test
  public void testJavacCommand() {
    ExecutionContext context = ExecutionContext.builder()
        .setProjectFilesystem(new ProjectFilesystem(new File(".")) {
          @Override
          public Function<Path, Path> getAbsolutifier() {
            return IdentityPathAbsolutifier.getIdentityAbsolutifier();
          }
        })
        .setConsole(new TestConsole())
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .setEnvironment(ImmutableMap.copyOf(System.getenv()))
        .build();

    JavacInMemoryStep firstOrder = createTestStep(BuildDependencies.FIRST_ORDER_ONLY);
    JavacInMemoryStep warn = createTestStep(BuildDependencies.WARN_ON_TRANSITIVE);
    JavacInMemoryStep transitive = createTestStep(BuildDependencies.TRANSITIVE);

    assertEquals(
        String.format("javac -target %s -source %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        firstOrder.getDescription(context));
    assertEquals(
        String.format("javac -target %s -source %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        warn.getDescription(context));
    assertEquals(
        String.format("javac -target %s -source %s -g -d . -classpath bar.jar%sfoo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, File.pathSeparator, PATH_TO_SRCS_LIST),
        transitive.getDescription(context));
  }

  private JavacInMemoryStep createTestStep(BuildDependencies buildDependencies) {
    return new JavacInMemoryStep(
          /* outputDirectory */ Paths.get("."),
          /* javaSourceFilePaths */ ImmutableSet.of(new TestSourcePath("foobar.java")),
          /* transitiveClasspathEntries */
            ImmutableSet.of(Paths.get("bar.jar"), Paths.get("foo.jar")),
          /* declaredClasspathEntries */ ImmutableSet.of(Paths.get("foo.jar")),
          /* JavacOptions */ JavacOptions.DEFAULTS,
          /* pathToOutputAbiFile */ Optional.<Path>absent(),
          /* invokingRule */ Optional.<String>absent(),
          /* buildDependencies */ buildDependencies,
          /* suggestBuildRules */ Optional.<JavacInMemoryStep.SuggestBuildRules>absent(),
          /* pathToSrcsList */ Optional.of(PATH_TO_SRCS_LIST));
  }
}
