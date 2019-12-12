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

package com.facebook.buck.jvm.java.abi.source.api;

import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskProxy;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoader;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoaderFactory;
import java.io.Writer;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

public interface FrontendOnlyJavacTaskProxy extends BuckJavacTaskProxy {
  static FrontendOnlyJavacTaskProxy getTask(
      PluginClassLoaderFactory loaderFactory,
      JavaCompiler compiler,
      Writer out,
      JavaFileManager fileManager,
      DiagnosticListener<? super JavaFileObject> diagnosticListener,
      Iterable<String> options,
      Iterable<String> classes,
      Iterable<? extends JavaFileObject> compilationUnits) {
    ErrorSuppressingDiagnosticListener errorSuppressingDiagnosticListener =
        new ErrorSuppressingDiagnosticListener(diagnosticListener);
    JavaCompiler.CompilationTask task =
        compiler.getTask(
            out,
            fileManager,
            errorSuppressingDiagnosticListener,
            options,
            classes,
            compilationUnits);
    errorSuppressingDiagnosticListener.setTask(task);

    PluginClassLoader loader = loaderFactory.getPluginClassLoader(task);
    Class<? extends FrontendOnlyJavacTaskProxy> proxyImplClass =
        loader.loadClass(
            "com.facebook.buck.jvm.java.abi.source.FrontendOnlyJavacTaskProxyImpl",
            FrontendOnlyJavacTaskProxy.class);

    try {
      return proxyImplClass.getConstructor(JavaCompiler.CompilationTask.class).newInstance(task);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}
