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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.net.MalformedURLException;
import java.net.URL;
import javax.tools.JavaCompiler;

public class JarBackedJavac extends Jsr199Javac {

  @AddToRuleKey private final String compilerClassName;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> classpath;

  public JarBackedJavac(String compilerClassName, ImmutableSet<SourcePath> classpath) {
    this.compilerClassName = compilerClassName;
    this.classpath = ImmutableSortedSet.copyOf(classpath);
  }

  @Override
  protected ResolvedJsr199Javac create(SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    ImmutableSortedSet<RelPath> resolvedClasspath =
        resolver.getAllAbsolutePaths(classpath).stream()
            .map(ruleCellRoot::relativize)
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));
    return new ResolvedJarBackedJavac(resolvedClasspath, compilerClassName);
  }

  /** Resolved jar backed (loaded from a jar specified in .buckconfig.) javac tool. */
  public static class ResolvedJarBackedJavac extends Jsr199Javac.ResolvedJsr199Javac {

    private final ImmutableSortedSet<RelPath> resolvedClasspath;
    private final String compilerClassName;

    public ResolvedJarBackedJavac(
        ImmutableSortedSet<RelPath> resolvedClasspath, String compilerClassName) {
      this.resolvedClasspath = resolvedClasspath;
      this.compilerClassName = compilerClassName;
    }

    @Override
    protected JavaCompiler createCompiler(JavacExecutionContext context) {
      AbsPath ruleCellRoot = context.getRuleCellRoot();
      ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
      ClassLoader compilerClassLoader =
          classLoaderCache.getClassLoaderForClassPath(
              ClassLoader.getSystemClassLoader(),
              resolvedClasspath.stream()
                  .map(ruleCellRoot::resolve)
                  .map(AbsPath::normalize)
                  .map(this::pathToUrl)
                  // Use "toString" since URL.equals does DNS lookups.
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.usingToString()))
                  .asList());
      try {
        return (JavaCompiler) compilerClassLoader.loadClass(compilerClassName).newInstance();
      } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
        throw new RuntimeException(ex);
      }
    }

    public Iterable<RelPath> getClasspath() {
      return resolvedClasspath;
    }

    public String getCompilerClassName() {
      return compilerClassName;
    }

    private URL pathToUrl(AbsPath p) {
      try {
        return p.toUri().toURL();
      } catch (MalformedURLException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
