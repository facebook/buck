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

import static com.facebook.buck.java.JavaBuckConfig.TARGETED_JAVA_VERSION;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Jsr199JavacTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");
  public static final ImmutableSet<Path> SOURCE_FILES = ImmutableSet.of(Paths.get("foobar.java"));

  @Test
  public void testJavacCommand() {
    ExecutionContext context = TestExecutionContext.newInstance();

    Jsr199Javac firstOrder = createTestStep();
    Jsr199Javac warn = createTestStep();
    Jsr199Javac transitive = createTestStep();

    assertEquals(
        String.format("javac -source %s -target %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        firstOrder.getDescription(
            context,
            getArgs().add("foo.jar").build(),
            SOURCE_FILES,
            Optional.of(PATH_TO_SRCS_LIST)));
    assertEquals(
        String.format("javac -source %s -target %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        warn.getDescription(
            context,
            getArgs().add("foo.jar").build(),
            SOURCE_FILES,
            Optional.of(PATH_TO_SRCS_LIST)));
    assertEquals(
        String.format("javac -source %s -target %s -g -d . -classpath bar.jar%sfoo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, File.pathSeparator, PATH_TO_SRCS_LIST),
        transitive.getDescription(
            context,
            getArgs().add("bar.jar" + File.pathSeparator + "foo.jar").build(),
            SOURCE_FILES,
            Optional.of(PATH_TO_SRCS_LIST)));
  }

  private Jsr199Javac createTestStep() {
    return new Jsr199Javac(Optional.<Path>absent());
  }

  private ImmutableList.Builder<String> getArgs() {
    return ImmutableList.<String>builder().add(
        "-source", TARGETED_JAVA_VERSION,
        "-target", TARGETED_JAVA_VERSION,
        "-g",
        "-d", ".",
        "-classpath");
  }
}
