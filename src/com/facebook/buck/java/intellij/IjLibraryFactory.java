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

package com.facebook.buck.java.intellij;

import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Filters out all of the targets which can be represented as IntelliJ prebuilts from the set of
 * TargetNodes and allows resolving those as dependencies of modules.
 */
public abstract class IjLibraryFactory {

  /**
   * From the supplied set of nodes finds all of the first-degree dependencies which can
   * be satisfied using a prebuilt library and returns a list of those libraries.
   *
   * @param targetNodes nodes whose dependencies to resolve.
   * @return list of dependencies which resolved to libraries.
   */
  public final ImmutableSet<IjLibrary> getLibraries(ImmutableSet<TargetNode<?>> targetNodes) {
    ImmutableSet.Builder<IjLibrary> librariesBuilder = ImmutableSet.builder();
    for (TargetNode<?> targetNode : targetNodes) {
      for (BuildTarget dep : targetNode.getDeps()) {
        if (targetNodes.contains(dep)) {
          continue;
        }
        Optional<IjLibrary> libraryOptional = getLibrary(dep.getBuildTarget());
        Optionals.addIfPresent(libraryOptional, librariesBuilder);
      }
    }
    return librariesBuilder.build();
  }

  public abstract Optional<IjLibrary> getLibrary(BuildTarget target);

  public static IjLibraryFactory create(ImmutableSet<TargetNode<?>> targetNodes) {
    return new IjLibraryFactoryImpl(targetNodes);
  }

  private static class IjLibraryFactoryImpl extends IjLibraryFactory {
    /**
     * Rule describing how to create a {@link IjLibrary} from a {@link TargetNode}.
     *
     * @param <T> TargetNode type.
     */
    interface IjLibraryRule<T> {
      BuildRuleType getType();

      void apply(TargetNode<T> targetNode, IjLibrary.Builder library);
    }

    private final Map<BuildRuleType, IjLibraryRule<?>> libraryRuleIndex = new HashMap<>();
    private final ImmutableMap<BuildTarget, IjLibrary> rulesToLibraries;

    /**
     * @param targetNodes nodes to consider when creating the list of prebuilts. It's fine for this
     *                    to be all of the nodes in the graph.
     */
    public IjLibraryFactoryImpl(ImmutableSet<TargetNode<?>> targetNodes) {
      addToIndex(new AndroidPrebuiltAarLibraryRule());
      addToIndex(new PrebuiltJarLibraryRule());

      rulesToLibraries = createLibraries(targetNodes);
    }

    private void addToIndex(IjLibraryRule<?> rule) {
      Preconditions.checkArgument(!libraryRuleIndex.containsKey(rule.getType()));
      libraryRuleIndex.put(rule.getType(), rule);
    }

    @Override
    public Optional<IjLibrary> getLibrary(BuildTarget target) {
      return Optional.fromNullable(rulesToLibraries.get(target));
    }

    /**
     * Find all of the prebuilt libraries in a set of targets.
     *
     * @param targetNodes targets to process.
     * @return lookup map containing {@link BuildTarget}s which correspond to prebuilt libraries.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ImmutableMap<BuildTarget, IjLibrary> createLibraries(
        ImmutableSet<TargetNode<?>> targetNodes) {
      Set<String> uniqueLibraryNamesSet = new HashSet<>();
      ImmutableMap.Builder<BuildTarget, IjLibrary> mapBuilder = new ImmutableMap.Builder<>();

      for (TargetNode<?> node : targetNodes) {
        IjLibraryRule<?> rule = libraryRuleIndex.get(node.getType());
        if (rule == null) {
          continue;
        }

        // Use a "library_" prefix so that the names don't clash with module names.
        String libraryName = Util.intelliJLibraryName(node.getBuildTarget());
        Preconditions.checkState(
            !uniqueLibraryNamesSet.contains(libraryName),
            "Trying to use the same library name for different targets.");

        IjLibrary.Builder libraryBuilder = IjLibrary.builder();
        rule.apply((TargetNode) node, libraryBuilder);
        libraryBuilder.setName(libraryName);
        mapBuilder.put(node.getBuildTarget(), libraryBuilder.build());
      }

      return mapBuilder.build();
    }

    private static class AndroidPrebuiltAarLibraryRule
        implements IjLibraryRule<AndroidPrebuiltAarDescription.Arg> {

      @Override
      public BuildRuleType getType() {
        return AndroidPrebuiltAarDescription.TYPE;
      }

      @Override
      public void apply(
          TargetNode<AndroidPrebuiltAarDescription.Arg> targetNode, IjLibrary.Builder library) {
        AndroidPrebuiltAarDescription.Arg arg = targetNode.getConstructorArg();
        library.setBinaryJar(arg.aar);
      }
    }

    private static class PrebuiltJarLibraryRule
        implements IjLibraryRule<PrebuiltJarDescription.Arg> {

      @Override
      public BuildRuleType getType() {
        return PrebuiltJarDescription.TYPE;
      }

      @Override
      public void apply(
          TargetNode<PrebuiltJarDescription.Arg> targetNode, IjLibrary.Builder library) {
        PrebuiltJarDescription.Arg arg = targetNode.getConstructorArg();
        library.setBinaryJar(arg.binaryJar);
        library.setSourceJar(arg.sourceJar);
        library.setJavadocUrl(arg.javadocUrl);
      }
    }
  }
}
