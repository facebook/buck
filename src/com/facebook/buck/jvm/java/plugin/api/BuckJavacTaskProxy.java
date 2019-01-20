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

package com.facebook.buck.jvm.java.plugin.api;

import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * {@link com.sun.source.util.JavacTask} is included with the compiler and is thus not directly
 * accessible from within Buck's class loader. This interface is used as a proxy within Buck's class
 * loader to allow access to commonly-used methods.
 */
public interface BuckJavacTaskProxy extends JavaCompiler.CompilationTask {
  static BuckJavacTaskProxy getTask(
      PluginClassLoaderFactory loaderFactory,
      JavaCompiler compiler,
      Writer out,
      JavaFileManager fileManager,
      DiagnosticListener<? super JavaFileObject> diagnosticListener,
      Iterable<String> options,
      Iterable<String> classes,
      Iterable<? extends JavaFileObject> compilationUnits) {
    JavaCompiler.CompilationTask task =
        compiler.getTask(out, fileManager, diagnosticListener, options, classes, compilationUnits);

    PluginClassLoader loader = loaderFactory.getPluginClassLoader(task);
    Class<? extends BuckJavacTaskProxy> proxyImplClass =
        loader.loadClass(
            "com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTaskProxyImpl",
            BuckJavacTaskProxy.class);

    try {
      return proxyImplClass.getConstructor(JavaCompiler.CompilationTask.class).newInstance(task);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  Iterable<CompilationUnitTreeProxy> parse() throws IOException;

  Iterable<? extends Element> enter() throws IOException;

  Iterable<? extends Element> analyze() throws IOException;

  Iterable<? extends JavaFileObject> generate() throws IOException;

  void setTaskListener(BuckJavacTaskListener taskListener);

  void addTaskListener(BuckJavacTaskListener taskListener);

  void removeTaskListener(BuckJavacTaskListener taskListener);

  void addPostEnterCallback(Consumer<Set<Element>> callback);

  ElementsExtended getElements();

  Types getTypes();

  Messager getMessager();
}
