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

package com.facebook.buck.jvm.java.testutil.compiler;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.jvm.java.abi.source.FrontendOnlyJavacTask;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacPlugin;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.google.common.base.Joiner;
import com.google.common.io.ByteStreams;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.processing.Processor;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.hamcrest.Matchers;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

/**
 * A {@link org.junit.Rule} for working with javac in tests.
 *
 * <p>Add it as a public field like this:
 *
 * <p>
 *
 * <pre>
 * &#64;Rule
 * public TestCompiler testCompiler = new TestCompiler();
 * </pre>
 */
public class TestCompiler extends ExternalResource implements AutoCloseable {
  private final TemporaryFolder inputFolder = new TemporaryFolder();
  private final TemporaryFolder outputFolder = new TemporaryFolder();
  private final Classes classes = new ClassesImpl(outputFolder);
  private final JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
  private final DiagnosticMessageCollector<JavaFileObject> diagnosticCollector =
      new DiagnosticMessageCollector<>();
  private final StandardJavaFileManager fileManager =
      javaCompiler.getStandardFileManager(diagnosticCollector, null, null);
  private final List<JavaFileObject> sourceFiles = new ArrayList<>();

  private TestCompiler classpathCompiler;
  private BuckJavacTask javacTask;
  private boolean useFrontendOnlyJavacTask = false;
  private boolean allowCompilationErrors = false;
  private Set<String> classpath = new LinkedHashSet<>();

  public void addClasspathFileContents(String fileName, String contents) throws IOException {
    if (javacTask != null) {
      throw new AssertionError("Can't add contents after creating the task");
    }

    if (classpathCompiler == null) {
      classpathCompiler = new TestCompiler();
      try {
        classpathCompiler.before();
      } catch (Throwable throwable) {
        throw new AssertionError(throwable);
      }
    }
    classpathCompiler.addSourceFileContents(fileName, contents);
    classpath.add(classpathCompiler.getOutputDir());
  }

  public void addClasspath(Collection<Path> paths) {
    paths.stream().map(Path::toString).forEach(classpath::add);
  }

  public void addSourceFileContents(String fileName, String contents) throws IOException {
    addSourceFileLines(fileName, contents);
  }

  public void addSourceFileLines(String fileName, String... lines) throws IOException {
    if (javacTask != null) {
      throw new AssertionError("Can't add contents after creating the task");
    }
    Path sourceFilePath = inputFolder.getRoot().toPath().resolve(fileName);

    sourceFilePath.toFile().getParentFile().mkdirs();
    Files.write(sourceFilePath, Arrays.asList(lines), StandardCharsets.UTF_8);

    fileManager.getJavaFileObjects(sourceFilePath.toFile()).forEach(sourceFiles::add);
  }

  public void addSourceFile(Path file) throws IOException {
    Path outputFile = outputFolder.getRoot().toPath().resolve(file.getFileName());
    ByteStreams.copy(Files.newInputStream(file), Files.newOutputStream(outputFile));

    fileManager.getJavaFileObjects(outputFile.toFile()).forEach(sourceFiles::add);
  }

  public void useFrontendOnlyJavacTask() {
    if (javacTask != null) {
      throw new AssertionError("Can't change the task type after creating it");
    }

    this.useFrontendOnlyJavacTask = true;
  }

  public JavaFileManager getFileManager() {
    return fileManager;
  }

  public void setTaskListener(TaskListener taskListener) {
    getJavacTask().setTaskListener(taskListener);
  }

  public void addPostEnterCallback(Consumer<Set<TypeElement>> callback) {
    getJavacTask().addPostEnterCallback(callback);
  }

  public void addPlugin(BuckJavacPlugin plugin, String... args) {
    BuckJavacTask task = getJavacTask();

    task.addPlugin(plugin, args);
  }

  public void setProcessors(List<Processor> processors) {
    getJavacTask().setProcessors(processors);
  }

  public void setAllowCompilationErrors(boolean allowCompileErrors) {
    this.allowCompilationErrors = allowCompileErrors;
  }

  public Iterable<? extends CompilationUnitTree> parse() throws IOException {
    return getJavacTask().parse();
  }

  public Iterable<? extends TypeElement> enter() throws IOException {
    BuckJavacTask javacTask = getJavacTask();

    try {
      @SuppressWarnings("unchecked")
      Iterable<? extends TypeElement> result =
          (Iterable<? extends TypeElement>)
              javacTask.getClass().getMethod("enter").invoke(javacTask);
      return result;
    } catch (IllegalAccessException | NoSuchMethodException e) {
      throw new AssertionError(e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }

      throw new AssertionError(e);
    }
  }

  public void compile() {
    boolean success = getJavacTask().call();
    if (!allowCompilationErrors) {
      if (!success) {
        fail(
            "Compilation failed! Diagnostics:\n"
                + getDiagnosticMessages().stream().collect(Collectors.joining("\n")));
      }
      assertTrue("Compilation encountered errors", success);
    }
  }

  public List<String> getDiagnosticMessages() {
    return diagnosticCollector.getDiagnosticMessages();
  }

  public Classes getClasses() {
    return classes;
  }

  public Elements getElements() {
    return getJavacTask().getElements();
  }

  public Trees getTrees() {
    BuckJavacTask javacTask = getJavacTask();
    return javacTask.getTrees();
  }

  public Types getTypes() {
    return getJavacTask().getTypes();
  }

  public BuckJavacTask getJavacTask() {
    if (javacTask == null) {
      compileClasspath();

      List<String> options = new ArrayList<>();
      options.add("-d");
      options.add(outputFolder.getRoot().toString());
      if (!classpath.isEmpty()) {
        options.add("-cp");
        options.add(Joiner.on(File.pathSeparatorChar).join(classpath));
      }

      JavacTask innerTask =
          (JavacTask)
              javaCompiler.getTask(
                  null, fileManager, diagnosticCollector, options, null, sourceFiles);

      if (useFrontendOnlyJavacTask) {
        javacTask = new FrontendOnlyJavacTask(innerTask);
      } else {
        javacTask = new BuckJavacTask(innerTask);
      }
    }

    return javacTask;
  }

  private String getOutputDir() {
    return outputFolder.getRoot().toString();
  }

  private void compileClasspath() {
    if (classpathCompiler == null) {
      return;
    }

    classpathCompiler.compile();
    assertThat(classpathCompiler.getDiagnosticMessages(), Matchers.empty());
  }

  public void init() {
    try {
      before();
    } catch (Throwable throwable) {
      throw new AssertionError(throwable);
    }
  }

  @Override
  protected void before() throws Throwable {
    inputFolder.create();
    outputFolder.create();
  }

  @Override
  protected void after() {
    if (classpathCompiler != null) {
      classpathCompiler.after();
    }
    outputFolder.delete();
    inputFolder.delete();
  }

  @Override
  public void close() {
    after();
  }

  /**
   * There's an issue with using the built in {@link javax.tools.DiagnosticCollector}. Grabbing the
   * {@link Diagnostic}s after compilation can lead to incorrect error messages. The references to
   * info that make up the Diagnostic's error message string may be GC-ed by the time we request the
   * message. To work around this, we use our own DiagnosticCollector that grabs the string of the
   * diagnostic at the time its reported and collect that instead.
   */
  private static class DiagnosticMessageCollector<S> implements DiagnosticListener<S> {
    private List<String> diagnostics = new ArrayList<>();

    @Override
    public void report(Diagnostic<? extends S> diagnostic) {
      diagnostics.add(diagnostic.toString());
    }

    private List<String> getDiagnosticMessages() {
      return diagnostics;
    }
  }
}
