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
import java.util.Arrays;

import javax.lang.model.util.Elements;
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
  protected TreeResolver treeResolver;
  protected TreeBackedElements treesElements;

  protected Iterable<? extends CompilationUnitTree> compile(String source) throws IOException {
    return compile(source, null);
  }

  protected Iterable<? extends CompilationUnitTree> compile(
      String source,
      TaskListenerFactory taskListenerFactory) throws IOException {
    File sourceFile = tempFolder.newFile("Foo.java");
    Files.write(source, sourceFile, StandardCharsets.UTF_8);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> sourceObjects =
        fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));

    javacTask =
        (JavacTask) compiler.getTask(null, fileManager, null, null, null, sourceObjects);

    if (taskListenerFactory != null) {
      javacTask.setTaskListener(taskListenerFactory.newTaskListener(javacTask));
    }

    trees = Trees.instance(javacTask);
    javacElements = javacTask.getElements();
    treeResolver = new TreeResolver(javacTask.getElements());
    treesElements = treeResolver.getElements();

    final Iterable<? extends CompilationUnitTree> compilationUnits = javacTask.parse();
    compilationUnits.forEach(tree -> treeResolver.enterTree(tree));

    // Make sure we've got elements for things. Technically this is going a little further than
    // the compiler ordinarily would by the time annotation processors get involved, but this
    // shouldn't matter for interface-level things. If need be there's a private method we can
    // reflect to to get more exact behavior.
    javacTask.analyze();

    return compilationUnits;
  }
}
