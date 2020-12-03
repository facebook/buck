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
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

/** Resolved JavacPluginProperties used in {@link JavacPipelineState} */
public class ResolvedJavacPluginProperties implements AddsToRuleKey {

  @AddToRuleKey private final JavacPluginProperties inner;
  @AddToRuleKey private final boolean canReuseClassLoader;
  @AddToRuleKey private final boolean doesNotAffectAbi;
  @AddToRuleKey private final boolean supportsAbiGenerationFromSource;
  @AddToRuleKey private final ImmutableSortedSet<String> processorNames;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final ImmutableList<RelPath> classpath;

  /**
   * All dependencies are already encoded in {@code inner} props, therefore we can exclude this
   * field from rule key. Also, this field will be empty for most of the plugins, since they don't
   * yet take advantage of it.
   */
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final ImmutableMap<String, RelPath> pathParams;

  public ResolvedJavacPluginProperties(
      JavacPluginProperties inner,
      boolean canReuseClassLoader,
      boolean doesNotAffectAbi,
      boolean supportsAbiGenerationFromSource,
      ImmutableSortedSet<String> processorNames,
      ImmutableList<RelPath> classpath,
      ImmutableMap<String, RelPath> pathParams) {
    this.inner = inner;
    this.canReuseClassLoader = canReuseClassLoader;
    this.doesNotAffectAbi = doesNotAffectAbi;
    this.supportsAbiGenerationFromSource = supportsAbiGenerationFromSource;
    this.processorNames = processorNames;
    this.classpath = classpath;
    this.pathParams = pathParams;
  }

  /** Creates {@link ResolvedJavacPluginProperties} */
  public static ResolvedJavacPluginProperties of(
      JavacPluginProperties properties, SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    return new ResolvedJavacPluginProperties(
        properties,
        properties.getCanReuseClassLoader(),
        properties.getDoesNotAffectAbi(),
        properties.getSupportsAbiGenerationFromSource(),
        properties.getProcessorNames(),
        toRelPathList(resolver, ruleCellRoot, properties.getClasspathEntries()),
        resolveSourcePathParams(resolver, ruleCellRoot, properties.getPathParams()));
  }

  /** Transforms given sorted set of {@link SourcePath} items into a list of {@link RelPath}. */
  private static ImmutableList<RelPath> toRelPathList(
      SourcePathResolverAdapter resolver,
      AbsPath ruleCellRoot,
      ImmutableSortedSet<SourcePath> sortedSet) {
    return sortedSet.stream()
        .map(resolver::getAbsolutePath)
        .map(ruleCellRoot::relativize)
        .collect(ImmutableList.toImmutableList());
  }

  public boolean getCanReuseClassLoader() {
    return canReuseClassLoader;
  }

  public boolean getDoesNotAffectAbi() {
    return doesNotAffectAbi;
  }

  public boolean getSupportAbiGenerationFromSource() {
    return supportsAbiGenerationFromSource;
  }

  public ImmutableSortedSet<String> getProcessorNames() {
    return processorNames;
  }

  /** Get the classpath for the plugin. */
  public ImmutableList<RelPath> getClasspath() {
    return classpath;
  }

  private static ImmutableMap<String, RelPath> resolveSourcePathParams(
      SourcePathResolverAdapter resolver, AbsPath root, JavacPluginPathParams pathParams) {
    ImmutableMap.Builder<String, RelPath> resolvedPathParams =
        ImmutableMap.builderWithExpectedSize(pathParams.getSize());

    for (Map.Entry<String, SourcePath> entry : pathParams.getSourcePathParams().entrySet()) {
      resolvedPathParams.put(
          entry.getKey(), root.relativize(resolver.getAbsolutePath(entry.getValue())));
    }

    resolvedPathParams.putAll(pathParams.getRelPathParams());

    return resolvedPathParams.build();
  }

  public ImmutableMap<String, RelPath> getPathParams() {
    return pathParams;
  }

  /** Get the javac plugin fields. */
  public JavacPluginJsr199Fields getJavacPluginJsr199Fields(AbsPath root) {
    return ImmutableJavacPluginJsr199Fields.ofImpl(
        getCanReuseClassLoader(),
        getProcessorNames(),
        ImmutableList.copyOf(toURLArray(getClasspath(), root)));
  }

  private static URL[] toURLArray(ImmutableList<RelPath> list, AbsPath root) {
    return list.stream()
        .map(root::resolve)
        .map(AbsPath::normalize)
        .map(ResolvedJavacPluginProperties::toUrl)
        .toArray(URL[]::new);
  }

  public static String getJoinedClasspath(
      ImmutableList<ResolvedJavacPluginProperties> resolvedProperties, AbsPath root) {
    return resolvedProperties.stream()
        .flatMap(p -> p.classpath.stream())
        .distinct()
        .map(root::resolve)
        .map(AbsPath::normalize)
        .map(ResolvedJavacPluginProperties::toUrl)
        .map(ResolvedJavacPluginProperties::toURI)
        .map(Paths::get)
        .map(Path::toString)
        .collect(Collectors.joining(File.pathSeparator));
  }

  private static URI toURI(URL url) {
    try {
      return url.toURI();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static URL toUrl(AbsPath absPath) {
    try {
      return absPath.toUri().toURL();
    } catch (MalformedURLException e) {
      // The paths we're being given should have all been resolved from the file system already.
      // We'd need to be unfortunate to get here. Bubble up a runtime exception.
      throw new RuntimeException(e);
    }
  }
}
