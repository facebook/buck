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

package com.facebook.buck.distributed;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigBuilder;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;

public class DistributedBuildStateTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void canReconstructConfig() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    BuckConfig buckConfig = new BuckConfig(
        new Config(ConfigBuilder.rawFromLines()),
        filesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.of("envKey", "envValue"));


    BuildJobState dump = DistributedBuildState.dump(buckConfig, emptyActionGraph());
    DistributedBuildState distributedBuildState = new DistributedBuildState(dump);

    assertThat(
        distributedBuildState.createBuckConfig(filesystem),
        Matchers.equalTo(buckConfig));
  }

  @Test
  public void throwsOnPlatformMismatch() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    BuckConfig buckConfig = new BuckConfig(
        new Config(ConfigBuilder.rawFromLines()),
        filesystem,
        Architecture.MIPSEL,
        Platform.UNKNOWN,
        ImmutableMap.of("envKey", "envValue"));

    BuildJobState dump = DistributedBuildState.dump(buckConfig, emptyActionGraph());
    DistributedBuildState distributedBuildState = new DistributedBuildState(dump);

    expectedException.expect(IllegalStateException.class);
    distributedBuildState.createBuckConfig(filesystem);
  }

  private DistributedBuildFileHashes emptyActionGraph() {
    ActionGraph actionGraph = new ActionGraph(ImmutableList.<BuildRule>of());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    return new DistributedBuildFileHashes(
        actionGraph,
        sourcePathResolver,
        DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem),
        MoreExecutors.newDirectExecutorService(),
        /* keySeed */ 0);
  }
}
