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
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.rules.modern.EmptyMemoizerDeserialization;
import com.facebook.buck.util.Memoizer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class ResolvedJavacPluginProperties implements AddsToRuleKey {

  @AddToRuleKey private final JavacPluginProperties inner;

  @CustomFieldBehavior(EmptyMemoizerDeserialization.class)
  private final Memoizer<ImmutableList<RelPath>> classpathSupplier;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private ImmutableList<RelPath> classpath;

  /**
   * All dependencies are already encoded in {@code inner} props, therefore we can exclude this
   * field from rule key. Also, this field will be empty for most of the plugins, since they don't
   * yet take advantage of it.
   */
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final ImmutableMap<String, RelPath> sourcePathParams;

  public ResolvedJavacPluginProperties(
      JavacPluginProperties inner, SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    this.inner = inner;
    this.classpathSupplier = new Memoizer<>();
    this.classpath = getClasspath(resolver, ruleCellRoot);
    this.sourcePathParams = resolveSourcePathParams(inner, resolver, ruleCellRoot);
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
  public ImmutableList<RelPath> getClasspath(SourcePathResolverAdapter resolver, AbsPath root) {
    return classpathSupplier.get(
        () ->
            inner.getClasspathEntries().stream()
                .map(resolver::getAbsolutePath)
                .map(root::relativize)
                .collect(ImmutableList.toImmutableList()));
  }

  private static ImmutableMap<String, RelPath> resolveSourcePathParams(
      JavacPluginProperties inner, SourcePathResolverAdapter resolver, AbsPath root) {
    return inner.getSourcePathParams().entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(
                Map.Entry::getKey,
                (entry) -> root.relativize(resolver.getAbsolutePath(entry.getValue()))));
  }

  public ImmutableMap<String, RelPath> getSourcePathParams() {
    return sourcePathParams;
  }

  private URL toUrl(AbsPath absPath) {
    try {
      return absPath.toUri().toURL();
    } catch (MalformedURLException e) {
      // The paths we're being given should have all been resolved from the file system already.
      // We'd need to be unfortunate to get here. Bubble up a runtime exception.
      throw new RuntimeException(e);
    }
  }

  public ImmutableSortedSet<SourcePath> getInputs() {
    return inner.getInputs();
  }

  /** Get the javac plugin fields. */
  public JavacPluginJsr199Fields getJavacPluginJsr199Fields(
      SourcePathResolverAdapter resolver, AbsPath root) {
    return ImmutableJavacPluginJsr199Fields.ofImpl(
        getCanReuseClassLoader(),
        getProcessorNames(),
        ImmutableList.copyOf(toURLArray(getClasspath(resolver, root), root)));
  }

  public static String getJoinedClasspath(
      ImmutableList<ResolvedJavacPluginProperties> resolvedProperties, AbsPath root) {
    return resolvedProperties.stream()
        .map(p -> p.toURLArray(p.classpath, root))
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

  /** Get the classpath for the plugin. */
  private URL[] toURLArray(ImmutableList<RelPath> classpath, AbsPath root) {
    return classpath.stream()
        .map(root::resolve)
        .map(AbsPath::normalize)
        .map(this::toUrl)
        .toArray(size -> new URL[size]);
  }
}
