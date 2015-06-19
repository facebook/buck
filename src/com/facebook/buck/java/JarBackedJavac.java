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

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import javax.tools.JavaCompiler;

public class JarBackedJavac extends Jsr199Javac {

  private final SourcePath javacJar;

  JarBackedJavac(SourcePath javacJar) {
    this.javacJar = javacJar;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder.setReflectively("javac", "jsr199")
        .setReflectively("javac.version", "in-memory")
        .setReflectively("javacjar", javacJar);
  }

  @Override
  protected JavaCompiler createCompiler(
      ExecutionContext context,
      SourcePathResolver resolver) {
    ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
    ClassLoader compilerClassLoader = classLoaderCache.getClassLoaderForClassPath(
        ClassLoader.getSystemClassLoader(),
        ImmutableList.of(resolver.getPath(javacJar)));
    try {
      return (JavaCompiler)
          compilerClassLoader.loadClass("com.sun.tools.javac.api.JavacTool")
              .newInstance();
    } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
      throw new RuntimeException(ex);
    }
  }

  @VisibleForTesting
  SourcePath getJavacJar() {
    return javacJar;
  }
}
