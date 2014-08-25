/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.thrift;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ThriftBuckConfigTest {

  private static FakeBuildRule createFakeBuildRule(
      String target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
  }

  @Test
  public void getCompilerFailsIfNothingSet() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup an empty thrift buck config, missing the compiler.
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Now try to lookup the compiler, which should fail since nothing was set.
    try {
      thriftBuckConfig.getCompiler(resolver);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getMessage().contains(
              ".buckconfig: must set either thrift:compiler_target or thrift:compiler_path"));
    }
  }

  @Test
  public void getCompilerFailsIfBothSet() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget thriftTarget = BuildTargetFactory.newInstance("//:thrift_target");
    Path thriftPath = Paths.get("thrift_path");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(thriftPath);

    // Setup an empty thrift buck config, missing the compiler.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of(
                "compiler_target", thriftTarget.toString(),
                "compiler_path", thriftPath.toString())),
        filesystem);
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Create a build rule that represents the thrift rule.
    FakeBuildRule thriftRule = createFakeBuildRule("//:thrift_target");
    resolver.addToIndex(thriftRule);

    // Now try to lookup the compiler, which should fail since nothing was set.
    try {
      thriftBuckConfig.getCompiler(resolver);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage().contains(
              "Cannot set both thrift:compiler_target and thrift:compiler_path"));
    }
  }

  @Test
  public void getCompilerSucceedsIfJustCompilerPathIsSet() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Path thriftPath = Paths.get("thrift_path");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(thriftPath);

    // Setup an empty thrift buck config, missing the compiler.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of("compiler_path", thriftPath.toString())),
        filesystem);
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Now try to lookup the compiler, which should succeed.
    SourcePath compiler = thriftBuckConfig.getCompiler(resolver);

    // Verify that the returned SourcePath wraps the compiler path correctly.
    assertTrue(compiler instanceof PathSourcePath);
    assertTrue(compiler.resolve().equals(filesystem.resolve(thriftPath)));
  }

  @Test
  public void getCompilerSucceedsIfJustCompilerTargetIsSet() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule thriftRule = createFakeBuildRule("//thrift:target");
    BuildTarget thriftTarget = thriftRule.getBuildTarget();

    // Add the thrift rule to the resolver.
    resolver.addToIndex(thriftRule);

    // Setup an empty thrift buck config, missing the compiler.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of("compiler_target", thriftTarget.toString())));
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Now try to lookup the compiler, which should succeed.
    SourcePath compiler = thriftBuckConfig.getCompiler(resolver);

    // Verify that the returned SourcePath wraps the compiler path correctly.
    assertTrue(compiler instanceof BuildRuleSourcePath);
    assertTrue(((BuildRuleSourcePath) compiler).getRule().equals(thriftRule));
  }

}
