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
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.EmptyMemoizerDeserialization;
import com.facebook.buck.util.Memoizer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;

public class ResolvedJavacPluginProperties implements AddsToRuleKey {
  @AddToRuleKey private final JavacPluginProperties inner;

  @CustomFieldBehavior(EmptyMemoizerDeserialization.class)
  private final Memoizer<URL[]> classpathSupplier;

  public ResolvedJavacPluginProperties(JavacPluginProperties inner) {
    this.inner = inner;
    this.classpathSupplier = new Memoizer<>();
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

  /** Get the classpath for the plugin. */
  public URL[] getClasspath(SourcePathResolver resolver, ProjectFilesystem filesystem) {
    return classpathSupplier.get(
        () ->
            inner.getClasspathEntries().stream()
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

  public ImmutableSortedSet<SourcePath> getInputs() {
    return inner.getInputs();
  }

  /** Get the javac plugin fields. */
  public JavacPluginJsr199Fields getJavacPluginJsr199Fields(
      SourcePathResolver resolver, ProjectFilesystem filesystem) {
    return JavacPluginJsr199Fields.builder()
        .setCanReuseClassLoader(getCanReuseClassLoader())
        .setClasspath(ImmutableList.copyOf(getClasspath(resolver, filesystem)))
        .setProcessorNames(getProcessorNames())
        .build();
  }

  public static String getJoinedClasspath(
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableList<ResolvedJavacPluginProperties> resolvedProperties) {
    return resolvedProperties.stream()
        .map(properties -> properties.getClasspath(resolver, filesystem))
        .flatMap(Arrays::stream)
        .distinct()
        .map(
            url -> {
              try {
                return url.toURI();
              } catch (URISyntaxException e) {
                throw new RuntimeException(e);
              }
            })
        .map(Paths::get)
        .map(Path::toString)
        .collect(Collectors.joining(File.pathSeparator));
  }
}
