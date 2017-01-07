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

package com.facebook.buck.jvm.java.abi.source;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public abstract class CompilerTreeApiTest {
  protected interface TaskListenerFactory {
    TaskListener newTaskListener(JavacTask task);
  }

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();
  protected JavacTask javacTask;
  protected Trees trees;
  protected Elements javacElements;
  protected Types javacTypes;
  protected TreeResolver treeResolver;
  protected TreeBackedElements treesElements;
  protected TreeBackedTypes treesTypes;

  protected final void initCompiler() throws IOException {
    compile(Collections.emptyMap(), null);
  }

  protected final Iterable<? extends CompilationUnitTree> compile(String source)
      throws IOException {
    return compile(ImmutableMap.of("Foo.java", source));
  }

  protected final Iterable<? extends CompilationUnitTree> compile(Map<String, String> sources)
      throws IOException {
    return compile(sources, null);
  }

  protected Iterable<? extends CompilationUnitTree> compile(
      Map<String, String> fileNamesToContents,
      TaskListenerFactory taskListenerFactory) throws IOException {

    List<File> sourceFiles = new ArrayList<>(fileNamesToContents.size());
    for (Map.Entry<String, String> fileNameToContents : fileNamesToContents.entrySet()) {
      String fileName = fileNameToContents.getKey();
      String contents = fileNameToContents.getValue();
      File sourceFile = tempFolder.newFile(fileName);
      Files.write(contents, sourceFile, StandardCharsets.UTF_8);
      sourceFiles.add(sourceFile);
    }

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> sourceObjects =
        fileManager.getJavaFileObjectsFromFiles(sourceFiles);

    javacTask =
        (JavacTask) compiler.getTask(null, fileManager, null, null, null, sourceObjects);

    if (taskListenerFactory != null) {
      javacTask.setTaskListener(taskListenerFactory.newTaskListener(javacTask));
    }

    trees = Trees.instance(javacTask);
    javacElements = javacTask.getElements();
    javacTypes = javacTask.getTypes();
    treeResolver = new TreeResolver(javacTask.getElements());
    treesElements = treeResolver.getElements();
    treesTypes = treeResolver.getTypes();

    final Iterable<? extends CompilationUnitTree> compilationUnits = javacTask.parse();
    compilationUnits.forEach(tree -> treeResolver.enterTree(tree));
    treeResolver.resolve();

    // Make sure we've got elements for things. Technically this is going a little further than
    // the compiler ordinarily would by the time annotation processors get involved, but this
    // shouldn't matter for interface-level things. If need be there's a private method we can
    // reflect to to get more exact behavior.
    javacTask.analyze();

    return compilationUnits;
  }
}
