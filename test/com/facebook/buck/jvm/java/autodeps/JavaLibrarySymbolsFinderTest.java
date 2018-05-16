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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;
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
  public void extractSymbolsFromSrcs() throws IOException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_symbols_finder", tmp)
        .setUp();
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    ImmutableSortedSet<SourcePath> srcs =
        ImmutableSortedSet.<SourcePath>naturalOrder()
            .addAll(
                Stream.of("Example1.java", "Example2.java")
                    .map(Paths::get)
                    .map(p -> PathSourcePath.of(projectFilesystem, p))
                    .iterator())
            .add(DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//foo:bar")))
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
  public void onlyNonGeneratedSrcsShouldAffectRuleKey() throws IOException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_symbols_finder", tmp)
        .setUp();
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Function<String, SourcePath> convert =
        src -> PathSourcePath.of(projectFilesystem, Paths.get(src));
    SourcePath example1 = convert.apply("Example1.java");
    SourcePath example2 = convert.apply("Example2.java");
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:GenEx.java");
    SourcePath generated = DefaultBuildTargetSourcePath.of(fakeBuildTarget);

    JavaLibrarySymbolsFinder example1Finder =
        new JavaLibrarySymbolsFinder(ImmutableSortedSet.of(example1), javaFileParser);
    JavaLibrarySymbolsFinder example2Finder =
        new JavaLibrarySymbolsFinder(ImmutableSortedSet.of(example2), javaFileParser);
    JavaLibrarySymbolsFinder example1AndGeneratedSrcFinder =
        new JavaLibrarySymbolsFinder(ImmutableSortedSet.of(example1, generated), javaFileParser);

    // Mock out calls to a SourcePathResolver so we can create a legitimate
    // DefaultRuleKeyFactory.
    SourcePathRuleFinder ruleFinder = createMock(SourcePathRuleFinder.class);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    expect(ruleFinder.getRule(anyObject(SourcePath.class)))
        .andAnswer(
            () -> {
              SourcePath input = (SourcePath) EasyMock.getCurrentArguments()[0];
              if (input instanceof ExplicitBuildTargetSourcePath) {
                return Optional.of(new FakeBuildRule(fakeBuildTarget));
              } else {
                return Optional.empty();
              }
            })
        .anyTimes();

    // Calculates the RuleKey for a JavaSymbolsRule with the specified JavaLibrarySymbolsFinder.
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    projectFilesystem, FileHashCacheMode.DEFAULT)));
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder);
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
