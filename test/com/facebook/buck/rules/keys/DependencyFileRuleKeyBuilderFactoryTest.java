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
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DependencyFileRuleKeyBuilderFactoryTest {

  @Test
  public void ruleKeyDoesNotChangeIfInputContentsChanges() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output");

    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setSrc(new PathSourcePath(filesystem, output))
            .build(resolver, filesystem);

    // Build a rule key with a particular hash set for the output for the above rule.
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                output,
                HashCode.fromInt(0)));
    RuleKey inputKey1 =
        new DependencyFileRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                output,
                HashCode.fromInt(1)));
    RuleKey inputKey2 =
        new DependencyFileRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
  }

  @Test
  public void ruleKeyDoesNotChangeIfInputContentsInRuleKeyAppendableChanges() {
    BuildRuleResolver resolver = new BuildRuleResolver();
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
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                output,
                HashCode.fromInt(0)));
    RuleKey inputKey1 =
        new DependencyFileRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    // Now, build a rule key with a different hash for the output for the above rule.
    hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                output,
                HashCode.fromInt(1)));
    RuleKey inputKey2 =
        new DependencyFileRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    assertThat(inputKey1, Matchers.equalTo(inputKey2));
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
