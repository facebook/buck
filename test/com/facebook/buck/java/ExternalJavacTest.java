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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExternalJavacTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");

  @Rule
  public DebuggableTemporaryFolder root = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();


  @Test
  public void testJavacCommand() {
    ExecutionContext context = ExecutionContext.builder()
        .setProjectFilesystem(new ProjectFilesystem(root.getRoot()) {
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

    ExternalJavacStep firstOrder = createTestStep(BuildDependencies.FIRST_ORDER_ONLY);
    ExternalJavacStep warn = createTestStep(BuildDependencies.WARN_ON_TRANSITIVE);
    ExternalJavacStep transitive = createTestStep(BuildDependencies.TRANSITIVE);

    assertEquals("fakeJavac -target 6 -source 6 -g -d . -classpath foo.jar @" + PATH_TO_SRCS_LIST,
        firstOrder.getDescription(context));
    assertEquals("fakeJavac -target 6 -source 6 -g -d . -classpath foo.jar @" + PATH_TO_SRCS_LIST,
        warn.getDescription(context));
    assertEquals("fakeJavac -target 6 -source 6 -g -d . -classpath bar.jar" + File.pathSeparator +
        "foo.jar @" + PATH_TO_SRCS_LIST,
        transitive.getDescription(context));
  }

  private ExternalJavacStep createTestStep(BuildDependencies buildDependencies) {
    return new ExternalJavacStep(
          /* outputDirectory */ Paths.get("."),
          /* javaSourceFilePaths */ ImmutableSet.of(new TestSourcePath("foobar.java")),
          /* transitiveClasspathEntries */
            ImmutableSet.of(Paths.get("bar.jar"), Paths.get("foo.jar")),
          /* declaredClasspathEntries */ ImmutableSet.of(Paths.get("foo.jar")),
          JavacOptions.builder(JavacOptions.DEFAULTS)
              .setJavaCompilerEnviornment(
                  new JavaCompilerEnvironment(
                      Optional.of(Paths.get("fakeJavac")),
                      Optional.<JavacVersion> absent(),
                      /* sourceLevel */ "6",
                      /* targetLevel */ "6"))
              .build(),
          /* pathToOutputAbiFile */ Optional.<Path>absent(),
          /* invokingRule */ Optional.<String>absent(),
          /* buildDependencies */ buildDependencies,
          /* suggestBuildRules */ Optional.<JavacInMemoryStep.SuggestBuildRules>absent(),
          /* pathToSrcsList */ Optional.of(PATH_TO_SRCS_LIST),
          /* target */ new BuildTarget("//fake", "target"),
          Optional.of(tmpFolder.getRoot().toPath()));
  }

}

