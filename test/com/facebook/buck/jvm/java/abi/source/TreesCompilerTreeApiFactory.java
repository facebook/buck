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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTest;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

class TreesCompilerTreeApiFactory implements CompilerTreeApiTest.CompilerTreeApiFactory {
  private final CompilerTreeApiTest.CompilerTreeApiFactory inner;

  public TreesCompilerTreeApiFactory(CompilerTreeApiTest.CompilerTreeApiFactory inner) {
    this.inner = inner;
  }

  @Override
  public JavacTask newJavacTask(
      JavaCompiler compiler,
      StandardJavaFileManager fileManager,
      DiagnosticCollector<JavaFileObject> diagnostics,
      Iterable<String> options,
      Iterable<? extends JavaFileObject> sourceObjects) {
    return new FrontendOnlyJavacTask(
        inner.newJavacTask(compiler, fileManager, diagnostics, options, sourceObjects));
  }

  @Override
  public Trees getTrees(JavacTask task) {
    return TreeBackedTrees.instance(task);
  }
}
