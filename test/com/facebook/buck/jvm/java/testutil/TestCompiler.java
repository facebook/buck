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

package com.facebook.buck.jvm.java.testutil;

import static org.junit.Assert.assertTrue;

import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * A {@link org.junit.Rule} for working with javac in tests.
 *
 * Add it as a public field like this:
 *
 * <pre>
 * &#64;Rule
 * public TestCompiler testCompiler = new TestCompiler();
 * </pre>
 */
public class TestCompiler extends ExternalResource {
  private final TemporaryFolder inputFolder = new TemporaryFolder();
  private final TemporaryFolder outputFolder = new TemporaryFolder();
  private final Classes classes = new ClassesImpl(outputFolder);
  private final JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
  private final StandardJavaFileManager fileManager =
      javaCompiler.getStandardFileManager(null, null, null);
  private final List<JavaFileObject> sourceFiles = new ArrayList<>();

  public void addSourceFileLines(String fileName, String... lines) throws IOException {
    Path sourceFilePath = inputFolder.getRoot().toPath().resolve(fileName);

    Files.write(sourceFilePath, Arrays.asList(lines), StandardCharsets.UTF_8);

    fileManager.getJavaFileObjects(sourceFilePath.toFile()).forEach(sourceFiles::add);
  }

  @Override
  protected void before() throws Throwable {
    inputFolder.create();
    outputFolder.create();
  }

  @Override
  protected void after() {
    outputFolder.delete();
    inputFolder.delete();
  }

  public void compile() {
    List<String> options = Arrays.asList("-d", outputFolder.getRoot().toString());
    JavaCompiler.CompilationTask task = javaCompiler.getTask(
        null,
        null,
        null,
        options,
        null,
        sourceFiles);
    assertTrue("Compilation encountered errors", task.call());
  }

  public Classes getClasses() {
    return classes;
  }
}
