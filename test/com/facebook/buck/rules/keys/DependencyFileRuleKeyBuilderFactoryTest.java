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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class DependencyFileRuleKeyBuilderFactoryTest {

  @Test
  public void ruleKeyDoesNotChangeIfInputContentsChanges() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output");

    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, output))
            .build(resolver, filesystem);

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            output,
            HashCode.fromInt(0)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory1 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey inputKey1 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory1)
            .build(
                rule,
                Optional.<ImmutableSet<SourcePath>>absent(),
                ImmutableList.<DependencyFileEntry>of()).getFirst();

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            output,
            HashCode.fromInt(1)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory2 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey inputKey2 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory2)
            .build(
                rule,
                Optional.<ImmutableSet<SourcePath>>absent(),
                ImmutableList.<DependencyFileEntry>of()).getFirst();

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void ruleKeyDoesNotChangeIfInputContentsInRuleKeyAppendableChanges() throws IOException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final Path output = Paths.get("output");

    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//:rule").build();
    BuildRule rule =
        new NoopBuildRule(params, pathResolver) {
          @AddToRuleKey
          RuleKeyAppendableWithInput input =
              new RuleKeyAppendableWithInput(new PathSourcePath(filesystem, output));
        };

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            filesystem.resolve(output),
            HashCode.fromInt(0)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory1 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey inputKey1 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory1)
            .build(
                rule,
                Optional.<ImmutableSet<SourcePath>>absent(),
                ImmutableList.<DependencyFileEntry>of()).getFirst();

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            filesystem.resolve(output),
            HashCode.fromInt(1)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory2 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey inputKey2 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory2)
            .build(
                rule,
                Optional.<ImmutableSet<SourcePath>>absent(),
                ImmutableList.<DependencyFileEntry>of()).getFirst();

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void manifestKeyDoesNotChangeIfPossibleDepFileContentsChange()throws IOException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final Path output = Paths.get("output");

    BuildRuleParams depParams = new FakeBuildRuleParamsBuilder("//:dep").build();
    final BuildRule dep = new FakeDepFileBuildRule(depParams, pathResolver)
        .setOutputPath(output);
    resolver.addToIndex(dep);

    final BuildTargetSourcePath inputSourcePath = new BuildTargetSourcePath(dep.getBuildTarget());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//:rule").build();
    FakeDepFileBuildRule rule = new FakeDepFileBuildRule(params, pathResolver) {
      {
        setPossibleInputPaths(ImmutableSet.<SourcePath>of(inputSourcePath));
      }

      @AddToRuleKey
      SourcePath input = inputSourcePath;
    };

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            filesystem.resolve(output),
            HashCode.fromInt(0)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory1 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey manifestKey1 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory1)
            .buildManifestKey(rule)
            .getFirst();

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            filesystem.resolve(output),
            HashCode.fromInt(1)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory2 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey manifestKey2 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory2)
            .buildManifestKey(rule)
            .getFirst();

    assertThat(manifestKey1, Matchers.equalTo(manifestKey2));
  }

  @Test
  public void manifestKeyChangesIfNotPossibleDepFileContentsChange() throws IOException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final Path localInput = Paths.get("localInput");

    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//:rule").build();
    final PathSourcePath inputSourcePath = new PathSourcePath(filesystem, localInput);
    BuildRule rule = new FakeDepFileBuildRule(params, pathResolver) {
      @AddToRuleKey
      SourcePath input = inputSourcePath;
    };

    // Build a rule key with a particular hash set for the localInput for the above rule.
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            filesystem.resolve(localInput),
            HashCode.fromInt(0)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory1 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey manifestKey1 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory1)
            .buildManifestKey(rule)
            .getFirst();

    // Now, build a rule key with a different hash for the localInput for the above rule.
    hashCache = new FakeFileHashCache(
        ImmutableMap.of(
            filesystem.resolve(localInput),
            HashCode.fromInt(1)));
    DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory2 = new DefaultRuleKeyBuilderFactory(
        hashCache,
        pathResolver);

    RuleKey manifestKey2 =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            hashCache,
            pathResolver,
            defaultRuleKeyBuilderFactory2)
            .buildManifestKey(rule)
            .getFirst();

    assertThat(manifestKey1, Matchers.not(Matchers.equalTo(manifestKey2)));
  }

  private static class FakeDepFileBuildRule
      extends AbstractBuildRule
      implements SupportsDependencyFileRuleKey {

    private Path outputPath;
    private ImmutableSet<SourcePath> possibleInputPaths = ImmutableSet.of();
    private ImmutableList<SourcePath> actualInputPaths = ImmutableList.of();

    protected FakeDepFileBuildRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }

    public FakeDepFileBuildRule setOutputPath(Path outputPath) {
      this.outputPath = outputPath;
      return this;
    }

    public FakeDepFileBuildRule setPossibleInputPaths(
        ImmutableSet<SourcePath> possibleInputPaths) {
      this.possibleInputPaths = possibleInputPaths;
      return this;
    }

    @Override
    public boolean useDependencyFileRuleKeys() {
      return true;
    }

    @Override
    public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() throws IOException {
      return Optional.of(possibleInputPaths);
    }

    @Override
    public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
      return actualInputPaths;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return null;
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return outputPath;
    }
  }

  private static class RuleKeyAppendableWithInput implements RuleKeyAppendable {

    private final SourcePath input;

    public RuleKeyAppendableWithInput(SourcePath input) {
      this.input = input;
    }

    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      return builder.setReflectively("input", input);
    }

  }

}
