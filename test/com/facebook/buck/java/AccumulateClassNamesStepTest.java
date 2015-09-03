/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class AccumulateClassNamesStepTest {

  private static final String SHA1_FOR_EMPTY_STRING = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testExecuteAccumulateClassNamesStepOnJarFile() throws IOException {
    // Create a JAR file.
    String name = "example.jar";
    File jarFile = tmp.newFile(name);
    try (JarOutputStream out = new JarOutputStream(
        new BufferedOutputStream(
            new FileOutputStream(jarFile)))) {
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
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    AccumulateClassNamesStep accumulateClassNamesStep = new AccumulateClassNamesStep(
        filesystem,
        Optional.of(Paths.get(name)),
        Paths.get("output.txt"));
    ExecutionContext context = TestExecutionContext.newInstance();
    accumulateClassNamesStep.execute(context);

    String contents = Files.toString(new File(tmp.getRoot(), "output.txt"), Charsets.UTF_8);
    String separator = AccumulateClassNamesStep.CLASS_NAME_HASH_CODE_SEPARATOR;
    assertEquals(
        "Verify that the contents are sorted alphabetically and ignore non-.class files.",
        Joiner.on('\n').join(
            "com/example/Bar" + separator + SHA1_FOR_EMPTY_STRING,
            "com/example/Foo" + separator + SHA1_FOR_EMPTY_STRING,
            "com/example/subpackage/Baz" + separator + SHA1_FOR_EMPTY_STRING) + '\n',
        contents);
  }

  @Test
  public void testExecuteAccumulateClassNamesStepOnDirectory() throws IOException {
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
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    AccumulateClassNamesStep accumulateClassNamesStep = new AccumulateClassNamesStep(
        filesystem,
        Optional.of(Paths.get(name)),
        Paths.get("output.txt"));
    ExecutionContext context = TestExecutionContext.newInstance();
    accumulateClassNamesStep.execute(context);

    String contents = Files.toString(new File(tmp.getRoot(), "output.txt"), Charsets.UTF_8);
    String separator = AccumulateClassNamesStep.CLASS_NAME_HASH_CODE_SEPARATOR;
    assertEquals(
        "Verify that the contents are sorted alphabetically and ignore non-.class files.",
        Joiner.on('\n').join(
            Paths.get("com/example/Bar") + separator + SHA1_FOR_EMPTY_STRING,
            Paths.get("com/example/Foo") + separator + SHA1_FOR_EMPTY_STRING,
            Paths.get("com/example/subpackage/Baz") + separator + SHA1_FOR_EMPTY_STRING) + '\n',
        contents);
  }
}
