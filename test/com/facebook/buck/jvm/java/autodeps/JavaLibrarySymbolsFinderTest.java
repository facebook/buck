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
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JavaLibrarySymbolsFinderTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private static final JavaFileParser javaFileParser = JavaFileParser.createJavaFileParser(
      JavacOptions.builder()
          .setSourceLevel("7")
          .setTargetLevel("7")
          .build());

  @Test
  public void extractSymbolsFromSrcs() throws IOException {
    TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "java_library_symbols_finder",
        tmp)
        .setUp();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());

    ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.<SourcePath>naturalOrder()
        .addAll(
            FluentIterable.from(ImmutableSet.of("Example1.java", "Example2.java"))
                .transform(Paths::get)
                .transform(SourcePaths.toSourcePath(projectFilesystem))
        )
        .add(new BuildTargetSourcePath(BuildTargetFactory.newInstance("//foo:bar")))
        .build();

    JavaLibrarySymbolsFinder finder = new JavaLibrarySymbolsFinder(
        srcs,
        javaFileParser,
        /* shouldRecordRequiredSymbols */ true);
    Symbols symbols = finder.extractSymbols();
    assertEquals(
        ImmutableSet.of("com.example.Example1", "com.example.Example2"),
        ImmutableSet.copyOf(symbols.provided));
    assertEquals(
        ImmutableSet.of("com.example.other.Bar", "com.example.other.Foo"),
        ImmutableSet.copyOf(symbols.required));
  }

  @Test
  @SuppressWarnings("PMD.PrematureDeclaration")
  public void onlyNonGeneratedSrcsShouldAffectRuleKey() throws IOException {
    TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "java_library_symbols_finder",
        tmp)
        .setUp();
    final ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());

    Function<String, SourcePath> convert =
        src -> SourcePaths.toSourcePath(projectFilesystem).apply(Paths.get(src));
    SourcePath example1 = convert.apply("Example1.java");
    SourcePath example2 = convert.apply("Example2.java");
    final BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:GenEx.java");
    SourcePath generated = new BuildTargetSourcePath(fakeBuildTarget);

    final boolean shouldRecordRequiredSymbols = true;
    JavaLibrarySymbolsFinder example1Finder = new JavaLibrarySymbolsFinder(
        ImmutableSortedSet.of(example1),
        javaFileParser,
        shouldRecordRequiredSymbols);
    JavaLibrarySymbolsFinder example2Finder = new JavaLibrarySymbolsFinder(
        ImmutableSortedSet.of(example2),
        javaFileParser,
        shouldRecordRequiredSymbols);
    JavaLibrarySymbolsFinder example1AndGeneratedSrcFinder = new JavaLibrarySymbolsFinder(
        ImmutableSortedSet.of(example1, generated),
        javaFileParser,
        shouldRecordRequiredSymbols);

    // Mock out calls to a SourcePathResolver so we can create a legitimate
    // DefaultRuleKeyBuilderFactory.
    final SourcePathResolver pathResolver = createMock(SourcePathResolver.class);
    expect(pathResolver.getRule(anyObject(SourcePath.class)))
        .andAnswer(() -> {
          SourcePath input = (SourcePath) EasyMock.getCurrentArguments()[0];
          if (input instanceof BuildTargetSourcePath) {
            return Optional.of(new FakeBuildRule(fakeBuildTarget, pathResolver));
          } else {
            return Optional.absent();
          }
        })
        .anyTimes();
    expect(pathResolver.getRelativePath(anyObject(SourcePath.class)))
        .andAnswer(() -> {
          SourcePath input = (SourcePath) EasyMock.getCurrentArguments()[0];
          assertTrue(input instanceof PathSourcePath);
          return ((PathSourcePath) input).getRelativePath();
        })
        .anyTimes();
    expect(pathResolver.getAbsolutePath(anyObject(SourcePath.class)))
        .andAnswer(() -> {
          SourcePath input = (SourcePath) EasyMock.getCurrentArguments()[0];
          assertTrue(input instanceof PathSourcePath);
          Path relativePath = ((PathSourcePath) input).getRelativePath();
          return projectFilesystem.resolve(relativePath);
        })
        .anyTimes();
    replay(pathResolver);

    // Calculates the RuleKey for a JavaSymbolsRule with the specified JavaLibrarySymbolsFinder.
    final FileHashCache fileHashCache =
        DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem);
    final DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(
        0,
        fileHashCache,
        pathResolver);
    Function<JavaLibrarySymbolsFinder, RuleKey> createRuleKey =
        finder -> {
          JavaSymbolsRule javaSymbolsRule = new JavaSymbolsRule(
              BuildTargetFactory.newInstance("//foo:rule"),
              finder,
              /* generatedSymbols */ ImmutableSortedSet.of(),
              ObjectMappers.newDefaultInstance(),
              projectFilesystem
          );
          return ruleKeyBuilderFactory.newInstance(javaSymbolsRule).build();
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
    verify(pathResolver);
  }
}
