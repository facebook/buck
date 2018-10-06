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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.file.RemoteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class InputBasedRuleKeyFactoryTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void ruleKeyDoesNotChangeWhenOnlyDependencyRuleKeyChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    Path depOutput = Paths.get("output");
    FakeBuildRule dep =
        graphBuilder.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), filesystem));
    dep.setOutputFile(depOutput.toString());
    filesystem.writeContentsToPath(
        "hello", pathResolver.getRelativePath(dep.getSourcePathToOutput()));

    FakeFileHashCache hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(depOutput), HashCode.fromInt(0)));

    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrcs(ImmutableList.of(dep.getSourcePathToOutput()))
            .build(graphBuilder, filesystem);

    RuleKey inputKey1 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    RuleKey inputKey2 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromPathSourceChanges() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output");

    BuildRule rule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(PathSourcePath.of(filesystem, output))
            .build(graphBuilder, filesystem);

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromBuildTargetSourcePathChanges() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder, filesystem);

    BuildRule rule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(dep.getSourcePathToOutput())
            .build(graphBuilder, filesystem);

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(Objects.requireNonNull(dep.getSourcePathToOutput())),
                HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(Objects.requireNonNull(dep.getSourcePathToOutput())),
                HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromPathSourcePathInRuleKeyAppendableChanges() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output");

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params = TestBuildRuleParams.create();
    BuildRule rule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, filesystem, params) {
          @AddToRuleKey
          RuleKeyAppendableWithInput input =
              new RuleKeyAppendableWithInput(PathSourcePath.of(filesystem, output));
        };

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromBuildTargetSourcePathInRuleKeyAppendableChanges()
      throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder, filesystem);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep));
    BuildRule rule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, filesystem, params) {
          @AddToRuleKey
          RuleKeyAppendableWithInput input =
              new RuleKeyAppendableWithInput(dep.getSourcePathToOutput());
        };

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(
                    Preconditions.checkNotNull(dep.getSourcePathToOutput())),
                HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(
                    Preconditions.checkNotNull(dep.getSourcePathToOutput())),
                HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void computingRuleKeyForRemoteFileShouldNotConsultHashLoader() throws URISyntaxException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildRule dep =
        new RemoteFile(
            BuildTargetFactory.newInstance("//:dep"),
            filesystem,
            TestBuildRuleParams.create(),
            (eventBus, uri, output) -> false,
            new URI("http://www.facebook.com"),
            Hashing.sha1().hashLong(42),
            "output.txt",
            RemoteFile.Type.EXPLODED_ZIP);
    graphBuilder.addToIndex(dep);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep));
    BuildRule rule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, filesystem, params) {
          @AddToRuleKey
          RuleKeyAppendableWithInput input =
              new RuleKeyAppendableWithInput(dep.getSourcePathToOutput());
        };

    FakeFileHashCache hashCache = new FakeFileHashCache(ImmutableMap.of());
    TestInputBasedRuleKeyFactory keyFactory =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder);
    keyFactory.build(rule);
  }

  @Test
  public void ruleKeyDoesNotChangeIfNonHashingSourcePathContentChanges()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem fileSystem = new FakeProjectFilesystem();

    // Build a rule key with a particular hash set for the output for the above rule.
    Path filePath = fileSystem.getPath("file.txt");
    FakeFileHashCache hashCache = new FakeFileHashCache(new HashMap<>());

    hashCache.set(filePath.toAbsolutePath(), HashCode.fromInt(0));
    PathSourcePath sourcePath = FakeSourcePath.of(fileSystem, filePath.toString());
    NonHashableSourcePathContainer nonHashablePath = new NonHashableSourcePathContainer(sourcePath);
    RuleKey inputKey1 = computeRuleKey(hashCache, pathResolver, ruleFinder, nonHashablePath);

    hashCache.set(filePath.toAbsolutePath(), HashCode.fromInt(1));
    RuleKey inputKey2 = computeRuleKey(hashCache, pathResolver, ruleFinder, nonHashablePath);

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void nestedSizeLimitExceptionHandled() throws Exception {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));

    Path inputFile = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[1024], inputFile);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params = TestBuildRuleParams.create();
    BuildRule rule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, filesystem, params) {
          @AddToRuleKey
          NestedRuleKeyAppendableWithInput input =
              new NestedRuleKeyAppendableWithInput(PathSourcePath.of(filesystem, inputFile));
        };

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new TestInputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200)
        .build(rule);
  }

  @Test
  public void ruleKeyNotCalculatedIfSizeLimitHit() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));

    // Create input that passes size limit.
    Path input = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[1024], input);

    // Construct rule which uses input.
    BuildRule rule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(PathSourcePath.of(filesystem, input))
            .build(graphBuilder, filesystem);

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new TestInputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200)
        .build(rule);
  }

  @Test
  public void ruleKeyNotCalculatedIfSizeLimitHitWithMultipleInputs() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));

    // Create inputs that combine to pass size limit.
    Path input1 = filesystem.getPath("input1");
    filesystem.writeBytesToPath(new byte[150], input1);
    Path input2 = filesystem.getPath("input2");
    filesystem.writeBytesToPath(new byte[150], input2);

    // Construct rule which uses inputs.
    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(filesystem, input1), PathSourcePath.of(filesystem, input2)))
            .build(graphBuilder, filesystem);

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new TestInputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200)
        .build(rule);
  }

  @Test
  public void ruleKeyNotCalculatedIfSizeLimitHitWithDirectory() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));

    // Create a directory of files which combine to pass size limit.
    Path input = filesystem.getPath("input");
    filesystem.mkdirs(input);
    filesystem.writeBytesToPath(new byte[150], input.resolve("file1"));
    filesystem.writeBytesToPath(new byte[150], input.resolve("file2"));

    // Construct rule which uses inputs.
    BuildRule rule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(PathSourcePath.of(filesystem, input))
            .build(graphBuilder, filesystem);

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new TestInputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200)
        .build(rule);
  }

  @Test
  public void ruleKeysForOversizedRules() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    int sizeLimit = 200;
    InputBasedRuleKeyFactory factory =
        new TestInputBasedRuleKeyFactory(
            fieldLoader, hashCache, pathResolver, ruleFinder, sizeLimit);
    // Create rule with inputs that make it go past the size limit, and verify the rule key factory
    // doesn't create a rule key.
    int tooLargeRuleSize = 300;
    assertThat(tooLargeRuleSize, Matchers.greaterThan(sizeLimit));
    Path tooLargeInput = filesystem.getPath("too_large_input");
    filesystem.writeBytesToPath(new byte[tooLargeRuleSize], tooLargeInput);
    BuildRule tooLargeRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:large_rule"))
            .setOut("out")
            .setSrc(PathSourcePath.of(filesystem, tooLargeInput))
            .build(graphBuilder, filesystem);
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    factory.build(tooLargeRule);
  }

  @Test
  public void ruleKeysForUndersizedRules() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    int sizeLimit = 200;
    InputBasedRuleKeyFactory factory =
        new TestInputBasedRuleKeyFactory(
            fieldLoader, hashCache, pathResolver, ruleFinder, sizeLimit);
    // Create a rule that doesn't pass the size limit and verify it creates a rule key.
    int smallEnoughRuleSize = 100;
    assertThat(smallEnoughRuleSize, Matchers.lessThan(sizeLimit));
    Path input = filesystem.getPath("small_enough_input");
    filesystem.writeBytesToPath(new byte[smallEnoughRuleSize], input);
    BuildRule smallEnoughRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:small_rule"))
            .setOut("out")
            .setSrc(PathSourcePath.of(filesystem, input))
            .build(graphBuilder, filesystem);
    factory.build(smallEnoughRule);
  }

  private static class RuleKeyAppendableWithInput implements AddsToRuleKey {

    @AddToRuleKey private final SourcePath input;

    public RuleKeyAppendableWithInput(SourcePath input) {
      this.input = input;
    }
  }

  private static class NestedRuleKeyAppendableWithInput implements AddsToRuleKey {

    @AddToRuleKey private final AddsToRuleKey input;

    public NestedRuleKeyAppendableWithInput(SourcePath input) {
      this.input = new RuleKeyAppendableWithInput(input);
    }
  }

  RuleKey computeRuleKey(
      FileHashCache hashCache,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Object... objects) {
    return new TestInputBasedRuleKeyFactory(hashCache, resolver, ruleFinder)
        .build(
            new FakeBuildRule("//fake:target") {
              @AddToRuleKey List<Object> ruleObjects = Arrays.asList(objects);
            });
  }
}
