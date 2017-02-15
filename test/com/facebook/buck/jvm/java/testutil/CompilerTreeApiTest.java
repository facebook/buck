/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Base class for tests that want to use the Compiler Tree API exposed by javac. */
public abstract class CompilerTreeApiTest {
  public interface TaskListenerFactory {
    TaskListener newTaskListener(JavacTask task);
  }

  /**
   * When run outside of IntelliJ, tests don't have the compiler (and thus the Compiler Tree API)
   * on their classpath. To get around that, we have a special test runner
   * ({@link CompilerTreeApiTestRunner}) that reloads each test with
   * a hacky custom {@link ClassLoader}.
   *
   * However, the test class must be able to successfully load using the default
   * {@link ClassLoader}, which means any accesses to the Compiler Tree API from the test must be
   * in places that aren't examined during initial class loading. So having a factory interface
   * is more than a nice abstraction here; it helps ensure that some of the problematic accesses
   * are far enough away from the initial class load as to not block it.
   */
  public interface CompilerTreeApiFactory {
    JavacTask newJavacTask(
        JavaCompiler compiler,
        StandardJavaFileManager fileManager,
        DiagnosticCollector<JavaFileObject> diagnostics,
        Iterable<String> options,
        Iterable<? extends JavaFileObject> sourceObjects);
    Trees getTrees(JavacTask task);
  }

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();
  @Rule
  public TemporaryFolder classpathSourceFolder = new TemporaryFolder();
  @Rule
  public TemporaryFolder classpathClassFolder = new TemporaryFolder();
  protected StandardJavaFileManager fileManager;
  protected JavacTask javacTask;
  protected DiagnosticCollector<JavaFileObject> diagnostics;
  protected Elements elements;
  protected Trees trees;
  protected Types types;

  protected CompilerTreeApiFactory newTreeApiFactory() {
    return new JavacCompilerTreeApiFactory();
  }

  protected final void initCompiler() throws IOException {
    initCompiler(Collections.emptyMap());
  }

  protected CompilerTreeApiFactory initCompiler(
      Map<String, String> fileNamesToContents) throws IOException {
    CompilerTreeApiFactory treeApiFactory = newTreeApiFactory();

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    fileManager = compiler.getStandardFileManager(null, null, null);
    diagnostics = new DiagnosticCollector<>();
    Iterable<String> options =
        Arrays.asList(new String[]{"-cp", classpathClassFolder.getRoot().toString()});
    Iterable<? extends JavaFileObject> sourceObjects =
        getJavaFileObjects(fileNamesToContents, tempFolder);

    javacTask = treeApiFactory.newJavacTask(
        compiler,
        fileManager,
        diagnostics,
        options,
        sourceObjects);
    trees = treeApiFactory.getTrees(javacTask);
    elements = javacTask.getElements();
    types = javacTask.getTypes();

    return treeApiFactory;
  }

  private Iterable<? extends JavaFileObject> getJavaFileObjects(
      Map<String, String> fileNamesToContents,
      TemporaryFolder tempFolder) throws IOException {
    List<File> sourceFiles = new ArrayList<>(fileNamesToContents.size());
    for (Map.Entry<String, String> fileNameToContents : fileNamesToContents.entrySet()) {
      Path filePath = tempFolder.getRoot().toPath().resolve(fileNameToContents.getKey());
      File parentDir = filePath.getParent().toFile();
      if (!parentDir.exists()) {
        assertTrue(parentDir.mkdirs());
      }

      String contents = fileNameToContents.getValue();
      File sourceFile = filePath.toFile();
      Files.write(contents, sourceFile, StandardCharsets.UTF_8);
      sourceFiles.add(sourceFile);
    }

    return fileManager.getJavaFileObjectsFromFiles(sourceFiles);
  }

  protected final Iterable<? extends CompilationUnitTree> compile(String source)
      throws IOException {
    return compile(ImmutableMap.of("Foo.java", source));
  }

  protected final Iterable<? extends CompilationUnitTree> compile(Map<String, String> sources)
      throws IOException {
    return compile(sources, null);
  }

  protected final Iterable<? extends CompilationUnitTree> compile(
      Map<String, String> fileNamesToContents,
      TaskListenerFactory taskListenerFactory) throws IOException {

    initCompiler(fileNamesToContents);

    if (taskListenerFactory != null) {
      javacTask.setTaskListener(taskListenerFactory.newTaskListener(javacTask));
    }

    // Suppress processor auto-discovery; it was picking up the immutables processor unnecessarily
    javacTask.setProcessors(Collections.emptyList());

    final Iterable<? extends CompilationUnitTree> compilationUnits = javacTask.parse();

    // Make sure we've got elements for things. Technically this is going a little further than
    // the compiler ordinarily would by the time annotation processors get involved, but this
    // shouldn't matter for interface-level things. If need be there's a private method we can
    // reflect to to get more exact behavior.
    //
    // Also, right now the implementation of analyze in the TreeBacked version of javacTask
    // (FrontendOnlyJavacTask) just does enter. So when these tests are run against one of those
    // we are actually going exactly as far as the compiler would.
    javacTask.analyze();

    return compilationUnits;
  }

  protected void withClasspath(
      Map<String, String> fileNamesToContents) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<String> options =
        Arrays.asList(new String[]{"-d", classpathClassFolder.getRoot().toString()});
    Iterable<? extends JavaFileObject> sourceObjects =
        getJavaFileObjects(fileNamesToContents, classpathSourceFolder);
    diagnostics = new DiagnosticCollector<>();

    compiler.getTask(null, fileManager, diagnostics, options, null, sourceObjects).call();
    assertThat(diagnostics.getDiagnostics(), Matchers.empty());
  }

  protected TypeMirror getTypeParameterUpperBound(String typeName, int typeParameterIndex) {
    TypeParameterElement typeParameter =
        elements.getTypeElement(typeName).getTypeParameters().get(typeParameterIndex);
    TypeVariable typeVariable = (TypeVariable) typeParameter.asType();

    return typeVariable.getUpperBound();
  }

  protected ExecutableElement findMethod(String name, TypeElement typeElement) {
    for (Element element : typeElement.getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD && element.getSimpleName().contentEquals(name)) {
        return (ExecutableElement) element;
      }
    }

    throw new IllegalArgumentException(String.format(
        "No such method in %s: %s",
        typeElement.getQualifiedName(),
        name));
  }

  protected VariableElement findField(String name, TypeElement typeElement) {
    for (Element element : typeElement.getEnclosedElements()) {
      if (element.getKind().isField() && element.getSimpleName().contentEquals(name)) {
        return (VariableElement) element;
      }
    }

    throw new IllegalArgumentException(String.format(
        "No such field in %s: %s",
        typeElement.getQualifiedName(),
        name));
  }

  protected void assertNameEquals(String expected, Name actual) {
    assertEquals(elements.getName(expected), actual);
  }

  protected void assertSameType(TypeMirror expected, TypeMirror actual) {
    if (!types.isSameType(expected, actual)) {
      fail(String.format("Types are not the same.\nExpected: %s\nActual: %s", expected, actual));
    }
  }

  protected void assertNotSameType(TypeMirror expected, TypeMirror actual) {
    if (types.isSameType(expected, actual)) {
      fail(String.format("Expected different types, but both were: %s", expected));
    }
  }

  private static class JavacCompilerTreeApiFactory implements CompilerTreeApiFactory {
    @Override
    public JavacTask newJavacTask(
        JavaCompiler compiler,
        StandardJavaFileManager fileManager,
        DiagnosticCollector<JavaFileObject> diagnostics,
        Iterable<String> options,
        Iterable<? extends JavaFileObject> sourceObjects) {
      return (JavacTask) compiler.getTask(
          null,
          fileManager,
          diagnostics,
          options,
          null,
          sourceObjects);
    }

    @Override
    public Trees getTrees(JavacTask task) {
      return Trees.instance(task);
    }
  }

}
