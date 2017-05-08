/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.autodeps;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;
import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;

public class JavaLibrarySymbolsFinderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static final JavaFileParser javaFileParser =
      JavaFileParser.createJavaFileParser(
          JavacOptions.builder().setSourceLevel("7").setTargetLevel("7").build());

  @Test
  public void extractSymbolsFromSrcs() throws InterruptedException, IOException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_symbols_finder", tmp)
        .setUp();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());

    ImmutableSortedSet<SourcePath> srcs =
        ImmutableSortedSet.<SourcePath>naturalOrder()
            .addAll(
                Stream.of("Example1.java", "Example2.java")
                    .map(Paths::get)
                    .map(p -> new PathSourcePath(projectFilesystem, p))
                    .iterator())
            .add(new DefaultBuildTargetSourcePath(BuildTargetFactory.newInstance("//foo:bar")))
            .build();

    JavaLibrarySymbolsFinder finder =
        new JavaLibrarySymbolsFinder(srcs, javaFileParser /* shouldRecordRequiredSymbols */);
    Symbols symbols = finder.extractSymbols();
    assertEquals(
        ImmutableSet.of("com.example.Example1", "com.example.Example2"),
        ImmutableSet.copyOf(symbols.provided));
  }

  @Test
  @SuppressWarnings("PMD.PrematureDeclaration")
  public void onlyNonGeneratedSrcsShouldAffectRuleKey() throws InterruptedException, IOException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_symbols_finder", tmp)
        .setUp();
    final ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());

    Function<String, SourcePath> convert =
        src -> new PathSourcePath(projectFilesystem, Paths.get(src));
    SourcePath example1 = convert.apply("Example1.java");
    SourcePath example2 = convert.apply("Example2.java");
    final BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:GenEx.java");
    SourcePath generated = new DefaultBuildTargetSourcePath(fakeBuildTarget);

    JavaLibrarySymbolsFinder example1Finder =
        new JavaLibrarySymbolsFinder(ImmutableSortedSet.of(example1), javaFileParser);
    JavaLibrarySymbolsFinder example2Finder =
        new JavaLibrarySymbolsFinder(ImmutableSortedSet.of(example2), javaFileParser);
    JavaLibrarySymbolsFinder example1AndGeneratedSrcFinder =
        new JavaLibrarySymbolsFinder(ImmutableSortedSet.of(example1, generated), javaFileParser);

    // Mock out calls to a SourcePathResolver so we can create a legitimate
    // DefaultRuleKeyFactory.
    final SourcePathRuleFinder ruleFinder = createMock(SourcePathRuleFinder.class);
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    expect(ruleFinder.getRule(anyObject(SourcePath.class)))
        .andAnswer(
            () -> {
              SourcePath input = (SourcePath) EasyMock.getCurrentArguments()[0];
              if (input instanceof ExplicitBuildTargetSourcePath) {
                return Optional.of(new FakeBuildRule(fakeBuildTarget, pathResolver));
              } else {
                return Optional.empty();
              }
            })
        .anyTimes();

    // Calculates the RuleKey for a JavaSymbolsRule with the specified JavaLibrarySymbolsFinder.
    final FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem)));
    final DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, fileHashCache, pathResolver, ruleFinder);
    Function<JavaLibrarySymbolsFinder, RuleKey> createRuleKey =
        finder -> {
          JavaSymbolsRule javaSymbolsRule =
              new JavaSymbolsRule(
                  BuildTargetFactory.newInstance("//foo:rule"), finder, projectFilesystem);
          return ruleKeyFactory.build(javaSymbolsRule);
        };

    RuleKey key1 = createRuleKey.apply(example1Finder);
    RuleKey key2 = createRuleKey.apply(example2Finder);
    RuleKey key3 = createRuleKey.apply(example1AndGeneratedSrcFinder);

    assertNotNull(key1);
    assertNotNull(key2);
    assertNotNull(key3);

    assertNotEquals(
        "Two instances of a JavaSymbolsRule with different srcs should change the RuleKey.",
        key1,
        key2);
    assertEquals(
        "Introducing an extra generated .java file to the srcs should not change the RuleKey.",
        key1,
        key3);
  }
}
