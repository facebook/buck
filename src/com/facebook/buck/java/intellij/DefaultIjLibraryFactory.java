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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Filters out all of the targets which can be represented as IntelliJ prebuilts from the set of
 * TargetNodes and allows resolving those as dependencies of modules.
 */
class DefaultIjLibraryFactory extends IjLibraryFactory {

  public interface IjLibraryFactoryResolver {
    /**
     * see {@link SourcePathResolver#getPath(SourcePath)}
     */
    Path getPath(SourcePath path);

    /**
     * @param targetNode node to look up.
     * @return path to the output of target but only if that path points to a .jar.
     */
    Optional<Path> getPathIfJavaLibrary(TargetNode<?> targetNode);
  }

  /**
   * Rule describing how to create a {@link IjLibrary} from a {@link TargetNode}.
   */
  private interface IjLibraryRule {
    void applyRule(TargetNode<?> targetNode, IjLibrary.Builder library);
  }

  /**
   * Rule describing how to create a {@link IjLibrary} from a {@link TargetNode}.
   * @param <T> the type of the TargetNode.
   */
  abstract class TypedIjLibraryRule<T> implements IjLibraryRule {
    abstract BuildRuleType getType();

    abstract void apply(TargetNode<T> targetNode, IjLibrary.Builder library);

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void applyRule(TargetNode<?> targetNode, IjLibrary.Builder library) {
      apply((TargetNode) targetNode, library);
    }
  }

  private Map<BuildRuleType, IjLibraryRule> libraryRuleIndex = new HashMap<>();
  private Set<String> uniqueLibraryNamesSet = new HashSet<>();
  private IjLibraryFactoryResolver libraryFactoryResolver;
  private Map<TargetNode<?>, Optional<IjLibrary>> libraryCache;

  public DefaultIjLibraryFactory(IjLibraryFactoryResolver libraryFactoryResolver) {
    this.libraryFactoryResolver = libraryFactoryResolver;

    addToIndex(new AndroidPrebuiltAarLibraryRule());
    addToIndex(new PrebuiltJarLibraryRule());

    libraryCache = new HashMap<>();
  }

  private void addToIndex(TypedIjLibraryRule<?> rule) {
    Preconditions.checkArgument(!libraryRuleIndex.containsKey(rule.getType()));
    libraryRuleIndex.put(rule.getType(), rule);
  }

  @Override
  public Optional<IjLibrary> getLibrary(TargetNode<?> target) {
    Optional<IjLibrary> library = libraryCache.get(target);
    if (library == null) {
      library = createLibrary(target);
      libraryCache.put(target, library);
    }
    return library;
  }

  private Optional<IjLibraryRule> getRule(TargetNode<?> targetNode) {
    IjLibraryRule rule = libraryRuleIndex.get(targetNode.getType());
    if (rule == null) {
      rule = libraryFactoryResolver.getPathIfJavaLibrary(targetNode)
          .transform(
              new Function<Path, IjLibraryRule>() {
                @Override
                public IjLibraryRule apply(Path input) {
                  return new JavaLibraryRule(input);
                }
              })
          .orNull();
    }
    return Optional.fromNullable(rule);
  }

  private Optional<IjLibrary> createLibrary(final TargetNode<?> targetNode) {
    return getRule(targetNode).transform(
        new Function<IjLibraryRule, IjLibrary>() {
          @Override
          public IjLibrary apply(IjLibraryRule rule) {
            // Use a "library_" prefix so that the names don't clash with module names.
            String libraryName = Util.intelliJLibraryName(targetNode.getBuildTarget());
            Preconditions.checkState(
                !uniqueLibraryNamesSet.contains(libraryName),
                "Trying to use the same library name for different targets.");

            IjLibrary.Builder libraryBuilder = IjLibrary.builder();
            rule.applyRule(targetNode, libraryBuilder);
            libraryBuilder.setName(libraryName);
            libraryBuilder.setTargets(ImmutableSet.<TargetNode<?>>of(targetNode));
            return libraryBuilder.build();
          }
        });
  }

  private static class JavaLibraryRule implements IjLibraryRule {
    private Path binaryJarPath;

    public JavaLibraryRule(Path binaryJarPath) {
      this.binaryJarPath = binaryJarPath;
    }

    @Override
    public void applyRule(
        TargetNode<?> targetNode, IjLibrary.Builder library) {
      library.setBinaryJar(binaryJarPath);
    }
  }

  private class AndroidPrebuiltAarLibraryRule
      extends TypedIjLibraryRule<AndroidPrebuiltAarDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidPrebuiltAarDescription.TYPE;
    }

    @Override
    public void apply(
        TargetNode<AndroidPrebuiltAarDescription.Arg> targetNode, IjLibrary.Builder library) {
      AndroidPrebuiltAarDescription.Arg arg = targetNode.getConstructorArg();
      library.setBinaryJar(libraryFactoryResolver.getPath(arg.aar));
    }
  }

  private class PrebuiltJarLibraryRule
      extends TypedIjLibraryRule<PrebuiltJarDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return PrebuiltJarDescription.TYPE;
    }

    @Override
    public void apply(
        TargetNode<PrebuiltJarDescription.Arg> targetNode, IjLibrary.Builder library) {
      PrebuiltJarDescription.Arg arg = targetNode.getConstructorArg();
      library.setBinaryJar(libraryFactoryResolver.getPath(arg.binaryJar));
      library.setSourceJar(
          arg.sourceJar.transform(
              new Function<SourcePath, Path>() {
                @Override
                public Path apply(SourcePath input) {
                  return libraryFactoryResolver.getPath(input);
                }
              }));
      library.setJavadocUrl(arg.javadocUrl);
    }
  }
}
