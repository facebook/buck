/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import javax.annotation.processing.Processor;

class AnnotationProcessorFactory implements AutoCloseable {
  private final JavacEventSink eventSink;
  private final ClassLoader compilerClassLoader;
  private final ClassLoaderCache globalClassLoaderCache;
  private final ClassLoaderCache localClassLoaderCache = new ClassLoaderCache();
  private final String buildTargetName;

  AnnotationProcessorFactory(
      JavacEventSink eventSink,
      ClassLoader compilerClassLoader,
      ClassLoaderCache globalClassLoaderCache,
      String buildTargetName) {
    this.eventSink = eventSink;
    this.compilerClassLoader = compilerClassLoader;
    this.globalClassLoaderCache = globalClassLoaderCache;
    this.buildTargetName = buildTargetName;
  }

  @Override
  public void close() throws IOException {
    localClassLoaderCache.close();
  }

  public List<Processor> createProcessors(
      JavacExecutionContext context, JavacPluginParams processorParams) {
    ImmutableList.Builder<Processor> builder = ImmutableList.builder();
    for (ResolvedJavacPluginProperties plugin : processorParams.getPluginProperties()) {
      ClassLoader classLoader = getClassLoaderForProcessorGroup(plugin, context.getRuleCellRoot());
      plugin.getProcessorNames().stream()
          .map(name -> createProcessor(classLoader, name))
          .forEach((v) -> builder.add(v));
    }
    return builder.build();
  }

  private Processor createProcessor(ClassLoader classLoader, String name) {
    try {
      Class<? extends Processor> aClass = classLoader.loadClass(name).asSubclass(Processor.class);
      return new TracingProcessorWrapper(eventSink, buildTargetName, aClass.newInstance());
    } catch (ReflectiveOperationException e) {
      // If this happens, then the build is really in trouble. Better warn the user.
      throw new HumanReadableException(
          e, "%s: javac unable to load annotation processor: %s", buildTargetName, name);
    }
  }

  @VisibleForTesting
  ClassLoader getClassLoaderForProcessorGroup(
      ResolvedJavacPluginProperties pluginParams, AbsPath relPathRoot) {
    ClassLoaderCache cache;
    // We can avoid lots of overhead in large builds by reusing the same classloader for java
    // plugins. However, some plugins use static variables in a way that assumes
    // there is only one instance running in the process at a time (or at all), and such plugin
    // would break running inside of Buck. So we default to creating a new ClassLoader
    // if any plugins meets those requirements.
    if (pluginParams.getCanReuseClassLoader()) {
      cache = globalClassLoaderCache;
    } else {
      cache = localClassLoaderCache;
    }
    return cache.getClassLoaderForClassPath(
        compilerClassLoader, pluginParams.toUrlClasspath(relPathRoot));
  }
}
