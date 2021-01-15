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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.AccumulateClassNamesStep;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AccumulateClassNamesStepTest {

  private static final String SHA1_FOR_EMPTY_STRING = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
  private static final HashCode SHA1_HASHCODE_FOR_EMPTY_STRING =
      HashCode.fromString(SHA1_FOR_EMPTY_STRING);

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private AbsPath ruleCellRoot;

  @Before
  public void setUp() {
    ruleCellRoot = AbsPath.of(tmp.getRoot().toPath().toAbsolutePath());
  }

  @Test
  public void testExecuteAccumulateClassNamesStepOnJarFile()
      throws IOException, InterruptedException {
    // Create a JAR file.
    String name = "example.jar";
    File jarFile = tmp.newFile(name);
    try (JarOutputStream out =
        new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)))) {
      out.putNextEntry(new ZipEntry("com/example/Foo.class"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/Bar.class"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/not_a_class.png"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/subpackage/Baz.class"));
      out.closeEntry();
    }

    // Create the AccumulateClassNamesStep and execute it.
    AccumulateClassNamesStep accumulateClassNamesStep =
        new AccumulateClassNamesStep(
            ImmutableSet.of(), Optional.of(RelPath.get(name)), RelPath.get("output.txt"));
    executeStep(accumulateClassNamesStep);

    String contents = Files.toString(new File(tmp.getRoot(), "output.txt"), StandardCharsets.UTF_8);
    String separator = AccumulateClassNamesStep.CLASS_NAME_HASH_CODE_SEPARATOR;
    assertEquals(
        "Verify that the contents are sorted alphabetically and ignore non-.class files.",
        Joiner.on('\n')
                .join(
                    "com/example/Bar" + separator + SHA1_FOR_EMPTY_STRING,
                    "com/example/Foo" + separator + SHA1_FOR_EMPTY_STRING,
                    "com/example/subpackage/Baz" + separator + SHA1_FOR_EMPTY_STRING)
            + '\n',
        contents);
  }

  @Test
  public void testExecuteAccumulateClassNamesStepOnDirectory()
      throws IOException, InterruptedException {
    // Create a directory.
    String name = "dir";
    tmp.newFolder(name);

    tmp.newFolder("dir", "com");
    tmp.newFolder("dir", "com", "example");
    tmp.newFolder("dir", "com", "example", "subpackage");

    tmp.newFile("dir/com/example/Foo.class");
    tmp.newFile("dir/com/example/Bar.class");
    tmp.newFile("dir/com/example/not_a_class.png");
    tmp.newFile("dir/com/example/subpackage/Baz.class");

    // Create the AccumulateClassNamesStep and execute it.
    AccumulateClassNamesStep accumulateClassNamesStep =
        new AccumulateClassNamesStep(
            ImmutableSet.of(), Optional.of(RelPath.get(name)), RelPath.get("output.txt"));
    executeStep(accumulateClassNamesStep);

    String contents = Files.toString(new File(tmp.getRoot(), "output.txt"), StandardCharsets.UTF_8);
    String separator = AccumulateClassNamesStep.CLASS_NAME_HASH_CODE_SEPARATOR;
    assertEquals(
        "Verify that the contents are sorted alphabetically and ignore non-.class files.",
        Joiner.on('\n')
                .join(
                    PathFormatter.pathWithUnixSeparators(Paths.get("com/example/Bar"))
                        + separator
                        + SHA1_FOR_EMPTY_STRING,
                    PathFormatter.pathWithUnixSeparators(Paths.get("com/example/Foo"))
                        + separator
                        + SHA1_FOR_EMPTY_STRING,
                    PathFormatter.pathWithUnixSeparators(Paths.get("com/example/subpackage/Baz"))
                        + separator
                        + SHA1_FOR_EMPTY_STRING)
            + '\n',
        contents);
  }

  @Test
  public void testParseClassHashesWithSpaces() throws IOException, InterruptedException {
    // Create a JAR file.
    String name = "example.jar";
    File jarFile = tmp.newFile(name);
    try (JarOutputStream out =
        new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)))) {
      out.putNextEntry(new ZipEntry("com/example/Foo.class"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/Foo$something with spaces$1.class"));
      out.closeEntry();
    }

    // Create the AccumulateClassNamesStep and execute it.
    AccumulateClassNamesStep accumulateClassNamesStep =
        new AccumulateClassNamesStep(
            ImmutableSet.of(), Optional.of(RelPath.get(name)), RelPath.get("output.txt"));
    executeStep(accumulateClassNamesStep);

    List<String> lines = ProjectFilesystemUtils.readLines(ruleCellRoot, Paths.get("output.txt"));
    ImmutableSortedMap<String, HashCode> parsedClassHashes =
        AccumulateClassNamesStep.parseClassHashes(lines);

    assertEquals(2, parsedClassHashes.size());
    assertEquals(SHA1_HASHCODE_FOR_EMPTY_STRING, parsedClassHashes.get("com/example/Foo"));
    assertEquals(
        SHA1_HASHCODE_FOR_EMPTY_STRING,
        parsedClassHashes.get("com/example/Foo$something with spaces$1"));
  }

  @Test
  public void testCalculateClassHashesIgnoresClassNames() throws IOException {
    // Create a JAR file.
    String name = "example.jar";
    File jarFile = tmp.newFile(name);
    try (JarOutputStream out =
        new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)))) {
      out.putNextEntry(new ZipEntry("com/example/Foo.class"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/Bar.class"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/not_a_class.png"));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("com/example/subpackage/Baz.class"));
      out.closeEntry();
    }

    StepExecutionContext executionContext = TestExecutionContext.newInstance(ruleCellRoot);
    // Create the AccumulateClassNamesStep and execute it.
    Optional<ImmutableSortedMap<String, HashCode>> classNamesToHashes =
        AccumulateClassNamesStep.calculateClassHashes(
            executionContext,
            ruleCellRoot,
            ImmutableSet.of(),
            ImmutableSet.of("com/example/Foo"),
            RelPath.get(name));

    assertTrue(classNamesToHashes.isPresent());
    assertEquals(
        ImmutableSet.of("com/example/Bar", "com/example/subpackage/Baz"),
        classNamesToHashes.get().keySet());
  }

  private void executeStep(IsolatedStep step) throws IOException, InterruptedException {
    step.executeIsolatedStep(TestExecutionContext.newInstance(ruleCellRoot));
  }
}
