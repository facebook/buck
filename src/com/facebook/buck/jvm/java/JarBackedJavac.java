/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import javax.tools.JavaCompiler;

public class JarBackedJavac extends Jsr199Javac {
  @AddToRuleKey private final String compilerClassName;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> classpath;

  public JarBackedJavac(String compilerClassName, Iterable<SourcePath> classpath) {
    this.compilerClassName = compilerClassName;
    this.classpath = ImmutableSortedSet.copyOf(classpath);
  }

  @Override
  protected JavaCompiler createCompiler(
      JavacExecutionContext context, SourcePathResolver resolver) {
    ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
    ClassLoader compilerClassLoader =
        classLoaderCache.getClassLoaderForClassPath(
            ClassLoader.getSystemClassLoader(),
            resolver
                .getAllAbsolutePaths(classpath)
                .stream()
                .map(JarBackedJavac::pathToUrl)
                // Use "toString" since URL.equals does DNS lookups.
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.usingToString()))
                .asList());
    try {
      return (JavaCompiler) compilerClassLoader.loadClass(compilerClassName).newInstance();
    } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
      throw new RuntimeException(ex);
    }
  }

  @VisibleForTesting
  Iterable<SourcePath> getCompilerClassPath() {
    return classpath;
  }

  @VisibleForTesting
  String getCompilerClassName() {
    return compilerClassName;
  }

  private static URL pathToUrl(Path p) {
    try {
      return p.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}
