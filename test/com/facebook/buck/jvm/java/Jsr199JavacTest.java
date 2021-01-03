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

import static com.facebook.buck.jvm.java.JavacLanguageLevelOptions.TARGETED_JAVA_VERSION;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Paths;
import org.easymock.EasyMockSupport;
import org.junit.Test;

public class Jsr199JavacTest extends EasyMockSupport {
  private static final RelPath PATH_TO_SRCS_LIST = RelPath.get("srcs_list");
  public static final ImmutableSortedSet<RelPath> SOURCE_FILES =
      ImmutableSortedSet.orderedBy(RelPath.comparator()).add(RelPath.get("foobar.java")).build();

  @Test
  public void testJavacCommand() {
    Jsr199Javac.ResolvedJsr199Javac firstOrder = createTestStep();
    Jsr199Javac.ResolvedJsr199Javac warn = createTestStep();
    Jsr199Javac.ResolvedJsr199Javac transitive = createTestStep();

    assertEquals(
        String.format(
            "javac -source %s -target %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        firstOrder.getDescription(
            getArgs().add("foo.jar").build(), SOURCE_FILES, PATH_TO_SRCS_LIST));
    assertEquals(
        String.format(
            "javac -source %s -target %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        warn.getDescription(getArgs().add("foo.jar").build(), SOURCE_FILES, PATH_TO_SRCS_LIST));
    assertEquals(
        String.format(
            "javac -source %s -target %s -g -d . -classpath bar.jar%sfoo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, File.pathSeparator, PATH_TO_SRCS_LIST),
        transitive.getDescription(
            getArgs().add("bar.jar" + File.pathSeparator + "foo.jar").build(),
            SOURCE_FILES,
            PATH_TO_SRCS_LIST));
  }

  private Jsr199Javac.ResolvedJsr199Javac createTestStep() {
    return new JdkProvidedInMemoryJavac()
        .resolve(
            new TestActionGraphBuilder().getSourcePathResolver(),
            AbsPath.of(Paths.get(".").toAbsolutePath()));
  }

  private ImmutableList.Builder<String> getArgs() {
    return ImmutableList.<String>builder()
        .add(
            "-source",
            TARGETED_JAVA_VERSION,
            "-target",
            TARGETED_JAVA_VERSION,
            "-g",
            "-d",
            ".",
            "-classpath");
  }
}
