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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShBinary;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ThriftBuckConfigTest {

  @Test
  public void getCompilerFailsIfNothingSet() {
    // Setup an empty thrift buck config, missing the compiler.
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Now try to lookup the compiler, which should fail since nothing was set.
    try {
      thriftBuckConfig.getCompiler(ThriftLibraryDescription.CompilerType.THRIFT, resolver);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getMessage().contains(".buckconfig: thrift:compiler must be set"));
    }
  }

  @Test
  public void getCompilerSucceedsIfJustCompilerPathIsSet() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Path thriftPath = Paths.get("thrift_path");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(thriftPath);

    // Setup an empty thrift buck config, missing the compiler.
    BuckConfig buckConfig = FakeBuckConfig.builder()
        .setSections(
            ImmutableMap.of(
                "thrift", ImmutableMap.of("compiler", thriftPath.toString())))
        .setFilesystem(filesystem)
        .build();

    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Now try to lookup the compiler, which should succeed.
    Tool compiler =
        thriftBuckConfig.getCompiler(ThriftLibraryDescription.CompilerType.THRIFT, resolver);

    // Verify that the returned SourcePath wraps the compiler path correctly.
    assertThat(compiler, Matchers.instanceOf(HashedFileTool.class));
    assertThat(
        compiler.getCommandPrefix(new SourcePathResolver(resolver)),
        Matchers.contains(thriftPath.toString()));
  }

  @Test
  public void getCompilerSucceedsIfJustCompilerTargetIsSet() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ShBinary thriftRule =
        (ShBinary) new ShBinaryBuilder(BuildTargetFactory.newInstance("//thrift:target"))
            .setMain(new FakeSourcePath("thrift.sh"))
            .build(resolver);

    // Add the thrift rule to the resolver.
    resolver.addToIndex(thriftRule);

    // Setup an empty thrift buck config, missing the compiler.
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(
        ImmutableMap.of(
            "thrift", ImmutableMap.of("compiler", thriftRule.getBuildTarget().toString()))).build();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Now try to lookup the compiler, which should succeed.
    Tool compiler =
        thriftBuckConfig.getCompiler(ThriftLibraryDescription.CompilerType.THRIFT, resolver);

    // Verify that the returned Tool wraps the compiler rule correctly.
    assertThat(
        compiler.getDeps(new SourcePathResolver(resolver)),
        Matchers.<BuildRule>contains(thriftRule));
  }

  @Test
  public void getCompilerThriftVsThrift2() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    // Setup an empty thrift buck config with thrift and thrift2 set..
    BuckConfig buckConfig = FakeBuckConfig.builder()
        .setSections(
            ImmutableMap.of(
                "thrift",
                ImmutableMap.of(
                    "compiler", "thrift1",
                    "compiler2", "thrift2")))
        .setFilesystem(filesystem)
        .build();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Verify that thrift1 and thrift2 are selected correctly.
    assertThat(
        thriftBuckConfig.getCompiler(ThriftLibraryDescription.CompilerType.THRIFT, resolver)
            .getCommandPrefix(new SourcePathResolver(resolver)),
        Matchers.contains("thrift1"));
    assertThat(
        thriftBuckConfig.getCompiler(ThriftLibraryDescription.CompilerType.THRIFT2, resolver)
            .getCommandPrefix(new SourcePathResolver(resolver)),
        Matchers.contains("thrift2"));
  }

  @Test
  public void getCompilerThrift2FallsbackToThrift() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    // Setup an empty thrift buck config with thrift and thrift2 set..
    BuckConfig buckConfig = FakeBuckConfig.builder()
        .setSections(
            ImmutableMap.of(
                "thrift",
                ImmutableMap.of("compiler", "thrift1")))
        .setFilesystem(filesystem)
        .build();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);

    // Verify that thrift2 falls back to the setting of thrift1.
    assertThat(
        thriftBuckConfig.getCompiler(ThriftLibraryDescription.CompilerType.THRIFT2, resolver)
            .getCommandPrefix(new SourcePathResolver(resolver)),
        Matchers.contains("thrift1"));
  }

}
