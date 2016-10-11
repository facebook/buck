/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class DefaultSuggestBuildRulesTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private BuildRuleResolver ruleResolver;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void before() {
    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    projectFilesystem = new ProjectFilesystem(tmp.getRoot().toPath());
  }

  @Test
  public void suggestTheTopologicallyNearestDependency() throws NoSuchBuildTargetException {
    // TODO(grumpyjames): stop duplicating source/symbol names if possible
    BuildRule libraryTwo = javaLibrary("//:libtwo", "com/facebook/Foo.java");
    BuildRule parent = javaLibrary("//:parent", "com/facebook/Foo.java", libraryTwo);
    BuildRule grandparent = javaLibrary("//:grandparent", "com/parent/OldManRiver.java", parent);

    ImmutableMap<Path, String> jarPathToSymbols = ImmutableMap.of(
        projectFilesystem.resolve(parent.getPathToOutput()), "com.facebook.Foo",
        projectFilesystem.resolve(libraryTwo.getPathToOutput()), "com.facebook.Foo");
    ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries =
        fromLibraries(libraryTwo, parent, grandparent);

    SuggestBuildRules.JarResolver jarResolver = createJarResolver(jarPathToSymbols);

    SuggestBuildRules suggestFn =
        DefaultSuggestBuildRules.createSuggestBuildFunction(
            jarResolver,
            ImmutableSet.of(),
            transitiveClasspathEntries.keySet(),
            ImmutableList.of(libraryTwo, parent, grandparent));

    final ImmutableSet<String> suggestions =
        suggestFn.suggest(ImmutableSet.of("com.facebook.Foo"));

    assertEquals(ImmutableSet.of("//:parent"), suggestions);
  }

  @Test
  public void suggestTopologicallyDistantDependency() throws NoSuchBuildTargetException {
    // TODO(grumpyjames): stop duplicating source/symbol names if possible
    BuildRule libraryTwo = javaLibrary("//:libtwo", "com/facebook/Bar.java");
    BuildRule parent = javaLibrary("//:parent", "com/facebook/Foo.java", libraryTwo);
    BuildRule grandparent = javaLibrary("//:grandparent", "com/parent/OldManRiver.java", parent);

    ImmutableMap<Path, String> jarPathToSymbols = ImmutableMap.of(
        projectFilesystem.resolve(parent.getPathToOutput()), "com.facebook.Foo",
        projectFilesystem.resolve(libraryTwo.getPathToOutput()), "com.facebook.Bar");
    ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries =
        fromLibraries(libraryTwo, parent, grandparent);

    SuggestBuildRules.JarResolver jarResolver = createJarResolver(jarPathToSymbols);

    SuggestBuildRules suggestFn =
        DefaultSuggestBuildRules.createSuggestBuildFunction(
            jarResolver,
            ImmutableSet.of(),
            transitiveClasspathEntries.keySet(),
            ImmutableList.of(libraryTwo, parent, grandparent));

    final ImmutableSet<String> suggestions =
        suggestFn.suggest(ImmutableSet.of("com.facebook.Bar"));

    assertEquals(ImmutableSet.of("//:libtwo"), suggestions);
  }

  private ImmutableSetMultimap<JavaLibrary, Path> fromLibraries(BuildRule...buildRules) {
    ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
        ImmutableSetMultimap.builder();

    for (BuildRule buildRule : buildRules) {
      //noinspection ConstantConditions
      builder.put((JavaLibrary) buildRule, projectFilesystem.resolve(buildRule.getPathToOutput()));
    }

    return builder.build();
  }

  private SuggestBuildRules.JarResolver createJarResolver(
      final ImmutableMap<Path, String> classToSymbols) {

    ImmutableSetMultimap.Builder<Path, String> resolveMapBuilder =
        ImmutableSetMultimap.builder();

    for (Map.Entry<Path, String> entry : classToSymbols.entrySet()) {
      String fullyQualified = entry.getValue();
      String packageName = fullyQualified.substring(0, fullyQualified.lastIndexOf('.'));
      String className = fullyQualified.substring(fullyQualified.lastIndexOf('.'));
      resolveMapBuilder.putAll(entry.getKey(), fullyQualified, packageName, className);
    }

    final ImmutableSetMultimap<Path, String> resolveMap = resolveMapBuilder.build();

    return new SuggestBuildRules.JarResolver() {
      @Override
      public ImmutableSet<String> resolve(Path absoluteClassPath) {
        if (resolveMap.containsKey(absoluteClassPath)) {
          return resolveMap.get(absoluteClassPath);
        } else {
          return ImmutableSet.of();
        }
      }
    };
  }

  private BuildRule javaLibrary(
      String name,
      String pathToClass,
      BuildRule... deps) throws NoSuchBuildTargetException {
    BuildTarget target = BuildTargetFactory.newInstance(name);

    JavaLibraryBuilder builder = JavaLibraryBuilder
        .createBuilder(target)
        .addSrc(Paths.get("java/src/" + pathToClass));
    for (BuildRule dep : deps) {
      builder = builder.addDep(dep.getBuildTarget());
    }
    return builder.build(ruleResolver, projectFilesystem);
  }
}
