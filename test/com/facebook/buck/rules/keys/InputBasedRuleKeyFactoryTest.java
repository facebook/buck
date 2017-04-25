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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.NonHashableSourcePathContainer;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class InputBasedRuleKeyFactoryTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void ruleKeyDoesNotChangeWhenOnlyDependencyRuleKeyChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    Path depOutput = Paths.get("output");
    FakeBuildRule dep =
        resolver.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), filesystem, pathResolver));
    dep.setOutputFile(depOutput.toString());
    filesystem.writeContentsToPath(
        "hello", pathResolver.getRelativePath(dep.getSourcePathToOutput()));

    FakeFileHashCache hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(depOutput), HashCode.fromInt(0)));

    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrcs(ImmutableList.of(dep.getSourcePathToOutput()))
            .build(resolver, filesystem);

    RuleKey inputKey1 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    RuleKey inputKey2 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromPathSourceChanges() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output");

    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, output))
            .build(resolver, filesystem);

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromBuildTargetSourcePathChanges() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver, filesystem);

    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(dep.getSourcePathToOutput())
            .build(resolver, filesystem);

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(
                    Preconditions.checkNotNull(dep.getSourcePathToOutput())),
                HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(
                    (Preconditions.checkNotNull(dep.getSourcePathToOutput()))),
                HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromPathSourcePathInRuleKeyAppendableChanges() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final Path output = Paths.get("output");

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder("//:rule").setProjectFilesystem(filesystem).build();
    BuildRule rule =
        new NoopBuildRule(params) {
          @AddToRuleKey
          RuleKeyAppendableWithInput input =
              new RuleKeyAppendableWithInput(new PathSourcePath(filesystem, output));
        };

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(0)));

    RuleKey inputKey1 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(output), HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyChangesIfInputContentsFromBuildTargetSourcePathInRuleKeyAppendableChanges()
      throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    final BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver, filesystem);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder("//:rule")
            .setDeclaredDeps(ImmutableSortedSet.of(dep))
            .setProjectFilesystem(filesystem)
            .build();
    BuildRule rule =
        new NoopBuildRule(params) {
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
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                pathResolver.getAbsolutePath(
                    Preconditions.checkNotNull(dep.getSourcePathToOutput())),
                HashCode.fromInt(1)));

    RuleKey inputKey2 =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(rule);

    assertThat(inputKey1, Matchers.not(Matchers.equalTo(inputKey2)));
  }

  @Test
  public void ruleKeyDoesNotChangeIfNonHashingSourcePathContentChanges()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    final ProjectFilesystem fileSystem = new FakeProjectFilesystem();

    // Build a rule key with a particular hash set for the output for the above rule.
    Path filePath = fileSystem.getPath("file.txt");
    FakeFileHashCache hashCache = new FakeFileHashCache(new HashMap<>());

    hashCache.set(filePath.toAbsolutePath(), HashCode.fromInt(0));
    PathSourcePath sourcePath = new PathSourcePath(fileSystem, filePath);
    NonHashableSourcePathContainer nonHashablePath = new NonHashableSourcePathContainer(sourcePath);
    RuleKey inputKey1 = computeRuleKey(hashCache, pathResolver, ruleFinder, nonHashablePath);

    hashCache.set(filePath.toAbsolutePath(), HashCode.fromInt(1));
    RuleKey inputKey2 = computeRuleKey(hashCache, pathResolver, ruleFinder, nonHashablePath);

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void nestedSizeLimitExceptionHandled() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));

    Path inputFile = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[1024], inputFile);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder("//:rule").setProjectFilesystem(filesystem).build();
    BuildRule rule =
        new NoopBuildRule(params) {
          @AddToRuleKey
          NestedRuleKeyAppendableWithInput input =
              new NestedRuleKeyAppendableWithInput(new PathSourcePath(filesystem, inputFile));
        };

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new InputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200).build(rule);
  }

  @Test
  public void ruleKeyNotCalculatedIfSizeLimitHit() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));

    // Create input that passes size limit.
    Path input = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[1024], input);

    // Construct rule which uses input.
    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, input))
            .build(resolver, filesystem);

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new InputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200).build(rule);
  }

  @Test
  public void ruleKeyNotCalculatedIfSizeLimitHitWithMultipleInputs() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));

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
                    new PathSourcePath(filesystem, input1), new PathSourcePath(filesystem, input2)))
            .build(resolver, filesystem);

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new InputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200).build(rule);
  }

  @Test
  public void ruleKeyNotCalculatedIfSizeLimitHitWithDirectory() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));

    // Create a directory of files which combine to pass size limit.
    Path input = filesystem.getPath("input");
    filesystem.mkdirs(input);
    filesystem.writeBytesToPath(new byte[150], input.resolve("file1"));
    filesystem.writeBytesToPath(new byte[150], input.resolve("file2"));

    // Construct rule which uses inputs.
    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, input))
            .build(resolver, filesystem);

    // Verify rule key isn't calculated.
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    new InputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, 200).build(rule);
  }

  @Test
  public void ruleKeysForOversizedRules() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    final int sizeLimit = 200;
    InputBasedRuleKeyFactory factory =
        new InputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, sizeLimit);
    // Create rule with inputs that make it go past the size limit, and verify the rule key factory
    // doesn't create a rule key.
    final int tooLargeRuleSize = 300;
    assertThat(tooLargeRuleSize, Matchers.greaterThan(sizeLimit));
    Path tooLargeInput = filesystem.getPath("too_large_input");
    filesystem.writeBytesToPath(new byte[tooLargeRuleSize], tooLargeInput);
    BuildRule tooLargeRule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:large_rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, tooLargeInput))
            .build(resolver, filesystem);
    expectedException.expect(SizeLimiter.SizeLimitException.class);
    factory.build(tooLargeRule);
  }

  @Test
  public void ruleKeysForUndersizedRules() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    final int sizeLimit = 200;
    InputBasedRuleKeyFactory factory =
        new InputBasedRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder, sizeLimit);
    // Create a rule that doesn't pass the size limit and verify it creates a rule key.
    final int smallEnoughRuleSize = 100;
    assertThat(smallEnoughRuleSize, Matchers.lessThan(sizeLimit));
    Path input = filesystem.getPath("small_enough_input");
    filesystem.writeBytesToPath(new byte[smallEnoughRuleSize], input);
    BuildRule smallEnoughRule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:small_rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, input))
            .build(resolver, filesystem);
    factory.build(smallEnoughRule);
  }

  private static class RuleKeyAppendableWithInput implements RuleKeyAppendable {

    private final SourcePath input;

    public RuleKeyAppendableWithInput(SourcePath input) {
      this.input = input;
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("input", input);
    }
  }

  private static class NestedRuleKeyAppendableWithInput implements RuleKeyAppendable {

    private final RuleKeyAppendable appendable;

    public NestedRuleKeyAppendableWithInput(SourcePath input) {
      this.appendable = new RuleKeyAppendableWithInput(input);
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("input", appendable);
    }
  }

  RuleKey computeRuleKey(
      FileHashCache hashCache,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Object... objects) {
    return new InputBasedRuleKeyFactory(0, hashCache, resolver, ruleFinder)
        .build(
            new FakeBuildRule("//fake:target", resolver) {
              @AddToRuleKey List<Object> ruleObjects = Arrays.asList(objects);
            });
  }
}
