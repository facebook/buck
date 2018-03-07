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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltJarSymbolsFinderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void extractSymbolsFromBinaryJar() throws InterruptedException, IOException {
    ImmutableSet<String> entries =
        ImmutableSet.of(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "com/",
            "com/facebook/",
            "com/facebook/buck/",
            "com/facebook/buck/cli/",
            "com/facebook/buck/cli/Main.class",
            "com/facebook/buck/cli/Main$1.class",
            "com/facebook/buck/cli/TestSelectorOptions.class",
            "com/facebook/buck/cli/TestSelectorOptions$TestSelectorsOptionHandler$1.class",
            "com/facebook/buck/cli/TestSelectorOptions$TestSelectorsOptionHandler.class");
    PrebuiltJarSymbolsFinder finder = createFinderForFileWithEntries("real.jar", entries);
    Symbols symbols = finder.extractSymbols();
    assertEquals(
        "Only entries that correspond to .class files for top-level types should be included.",
        ImmutableSet.of("com.facebook.buck.cli.Main", "com.facebook.buck.cli.TestSelectorOptions"),
        ImmutableSet.copyOf(symbols.provided));
  }

  @Test
  public void extractSymbolsFromGeneratedBinaryJar() throws IOException {
    PrebuiltJarSymbolsFinder finder = createFinderForGeneratedJar("//foo:jar_genrule");

    Symbols symbols = finder.extractSymbols();
    assertTrue(
        "There should be no provided symbols if the binaryJar is not a PathSourcePath.",
        Iterables.isEmpty(symbols.provided));
  }

  @Test
  public void contentsOfBinaryJarShouldAffectRuleKey() {
    // The path to the JAR file to use as the binaryJar of the PrebuiltJarSymbolsFinder.
    Path relativePathToJar = Paths.get("common.jar");
    Path absolutePathToJar = tmp.getRoot().resolve(relativePathToJar);

    // Mock out calls to a SourcePathResolver so we can create a legitimate
    // DefaultRuleKeyFactory.
    SourcePathRuleFinder ruleFinder = createMock(SourcePathRuleFinder.class);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    createMock(SourcePathResolver.class);
    expect(ruleFinder.getRule(anyObject(SourcePath.class))).andReturn(Optional.empty()).anyTimes();

    // Calculates the RuleKey for a JavaSymbolsRule with a PrebuiltJarSymbolsFinder whose binaryJar
    // is a JAR file with the specified entries.
    Function<ImmutableSet<String>, RuleKey> createRuleKey =
        entries -> {
          File jarFile = absolutePathToJar.toFile();

          JavaSymbolsRule javaSymbolsRule;
          FakeFileHashCache fileHashCache;
          try {
            PrebuiltJarSymbolsFinder finder =
                createFinderForFileWithEntries(relativePathToJar.getFileName().toString(), entries);
            HashCode hash = Files.hash(jarFile, Hashing.sha1());
            Map<Path, HashCode> pathsToHashes = ImmutableMap.of(absolutePathToJar, hash);
            fileHashCache = new FakeFileHashCache(pathsToHashes);

            javaSymbolsRule =
                new JavaSymbolsRule(
                    BuildTargetFactory.newInstance("//foo:rule"),
                    finder,
                    TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          RuleKey ruleKey =
              new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder)
                  .build(javaSymbolsRule);
          jarFile.delete();

          return ruleKey;
        };

    RuleKey key1 = createRuleKey.apply(ImmutableSet.of("entry1", "entry2"));
    RuleKey key2 = createRuleKey.apply(ImmutableSet.of("entry1", "entry2"));
    RuleKey key3 = createRuleKey.apply(ImmutableSet.of("entry1", "entry2", "entry3"));

    assertNotNull(key1);
    assertNotNull(key2);
    assertNotNull(key3);

    assertEquals(
        "Two instances of a JavaSymbolsRule with the same inputs should have the same RuleKey.",
        key1,
        key2);
    assertNotEquals(
        "Changing the contents of the binaryJar for the PrebuiltJarSymbolsFinder should change "
            + "the RuleKey of the JavaSymbolsRule that contains it.",
        key1,
        key3);
  }

  @Test
  public void generatedBinaryJarShouldNotAffectRuleKey() {
    SourcePathResolver pathResolver = null;
    SourcePathRuleFinder ruleFinder = null;

    Path jarFile = tmp.getRoot().resolve("common.jar");
    Map<Path, HashCode> pathsToHashes =
        ImmutableMap.of(jarFile, HashCode.fromString(Strings.repeat("abcd", 10)));
    FakeFileHashCache fileHashCache = new FakeFileHashCache(pathsToHashes);

    JavaSymbolsRule javaSymbolsRule1 =
        new JavaSymbolsRule(
            BuildTargetFactory.newInstance("//foo:rule"),
            createFinderForGeneratedJar("//foo:jar_genrule1"),
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()));

    RuleKey key1 =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder)
            .build(javaSymbolsRule1);

    JavaSymbolsRule javaSymbolsRule2 =
        new JavaSymbolsRule(
            BuildTargetFactory.newInstance("//foo:rule"),
            createFinderForGeneratedJar("//foo:jar_genrule2"),
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()));
    RuleKey key2 =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder)
            .build(javaSymbolsRule2);

    assertNotNull(key1);
    assertNotNull(key2);
    assertEquals(
        "Keys should match even though different BuildTargetSourcePaths are used.", key1, key2);
  }

  private PrebuiltJarSymbolsFinder createFinderForFileWithEntries(
      String jarFileName, Iterable<String> entries) throws IOException {
    Clock clock = FakeClock.doNotCare();
    Path jarFile = tmp.newFile(jarFileName);
    try (OutputStream stream =
            new BufferedOutputStream(java.nio.file.Files.newOutputStream(jarFile));
        CustomZipOutputStream out =
            ZipOutputStreams.newOutputStream(
                stream, ZipOutputStreams.HandleDuplicates.THROW_EXCEPTION, clock)) {
      for (String entry : entries) {
        out.putNextEntry(new ZipEntry(entry));
      }
    }

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    SourcePath sourcePath = PathSourcePath.of(filesystem, Paths.get(jarFileName));
    return new PrebuiltJarSymbolsFinder(sourcePath);
  }

  private PrebuiltJarSymbolsFinder createFinderForGeneratedJar(String target) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(target);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(buildTarget);
    return new PrebuiltJarSymbolsFinder(sourcePath);
  }
}
