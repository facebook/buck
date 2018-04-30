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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.function.Supplier;

public class ResolvedJavacPluginProperties implements AddsToRuleKey {
  @AddToRuleKey private final AbstractJavacPluginProperties inner;
  private final Supplier<URL[]> classpathSupplier;

  public ResolvedJavacPluginProperties(
      AbstractJavacPluginProperties inner,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver) {
    this.inner = inner;

    classpathSupplier =
        MoreSuppliers.memoize(
            () ->
                inner
                    .getClasspathEntries()
                    .stream()
                    .map(resolver::getAbsolutePath)
                    .map(filesystem::resolve)
                    .map(Path::toUri)
                    .map(
                        uri -> {
                          try {
                            return uri.toURL();
                          } catch (MalformedURLException e) {
                            // The paths we're being given should have all been resolved from the
                            // file
                            // system already. We'd need to be unfortunate to get here. Bubble up a
                            // runtime
                            // exception.
                            throw new RuntimeException(e);
                          }
                        })
                    .toArray(size -> new URL[size]));
  }

  public boolean getCanReuseClassLoader() {
    return inner.getCanReuseClassLoader();
  }

  public boolean getDoesNotAffectAbi() {
    return inner.getDoesNotAffectAbi();
  }

  public boolean getSupportAbiGenerationFromSource() {
    return inner.getSupportsAbiGenerationFromSource();
  }

  public ImmutableSortedSet<String> getProcessorNames() {
    return inner.getProcessorNames();
  }

  public URL[] getClasspath() {
    return classpathSupplier.get();
  }

  public ImmutableSortedSet<SourcePath> getInputs() {
    return inner.getInputs();
  }

  public JavacPluginJsr199Fields getJavacPluginJsr199Fields() {
    return JavacPluginJsr199Fields.builder()
        .setCanReuseClassLoader(getCanReuseClassLoader())
        .setClasspath(ImmutableList.copyOf(getClasspath()))
        .setProcessorNames(getProcessorNames())
        .build();
  }
}
