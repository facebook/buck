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

package com.facebook.buck.java;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.tools.JavaCompiler;

public class JarBackedJavac extends Jsr199Javac {

  private final String compilerClassName;
  private final Iterable<SourcePath> classpath;

  JarBackedJavac(String compilerClassName, Iterable<SourcePath> classpath) {
    this.compilerClassName = compilerClassName;
    this.classpath = classpath;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder.setReflectively("javac", "jar-backed-jsr199")
        .setReflectively("javac.version", "in-memory")
        .setReflectively("javac.classname", compilerClassName)
        .setReflectively("javac.classpath", classpath);
  }

  @Override
  protected JavaCompiler createCompiler(
      ExecutionContext context,
      final SourcePathResolver resolver) {
    ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
    ClassLoader compilerClassLoader = classLoaderCache.getClassLoaderForClassPath(
        ClassLoader.getSystemClassLoader(),
        FluentIterable.from(classpath)
            .transformAndConcat(
                new Function<SourcePath, Collection<Path>>() {
                  @Override
                  public Collection<Path> apply(SourcePath input) {
                    Set<Path> paths = new HashSet<>();
                    Optional<BuildRule> rule = resolver.getRule(input);
                    if (rule instanceof JavaLibrary) {
                      paths.addAll(((JavaLibrary) rule).getTransitiveClasspathEntries().values());
                    } else {
                      paths.add(resolver.getPath(input));
                    }
                    return paths;
                  }
                })
            .toSortedSet(Ordering.natural())
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
}
