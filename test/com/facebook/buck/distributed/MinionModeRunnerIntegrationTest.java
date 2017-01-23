/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.FakeCellPathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class MinionModeRunnerIntegrationTest {

  private static final BuildId BUILD_ID = ThriftCoordinatorServerIntegrationTest.BUILD_ID;
  private final CellPathResolver cellPathResolver =
      new FakeCellPathResolver(new FakeProjectFilesystem());

  @Test(timeout = 2000L)
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    try (ThriftCoordinatorServer server = createServer()) {
      server.start();
      LocalBuilderImpl localBuilder = new LocalBuilderImpl();
      MinionModeRunner minion = new MinionModeRunner(
          "localhost",
          server.getPort(),
          cellPathResolver,
          localBuilder,
          BUILD_ID);
      int exitCode = minion.runAndReturnExitCode();
      Assert.assertEquals(0, exitCode);
      Assert.assertEquals(3, localBuilder.getCallArguments().size());
      Assert.assertEquals(
          BuildTargetsQueueTest.TARGET_NAME,
          localBuilder.getCallArguments().get(2).iterator().next().getFullyQualifiedName());
    }
  }

  private ThriftCoordinatorServer createServer() throws NoSuchBuildTargetException, IOException {
    BuildTargetsQueue queue = BuildTargetsQueueTest.createDiamondDependencyQueue();
    return ThriftCoordinatorServerIntegrationTest.createServerOnRandomPort(queue);
  }

  public static class LocalBuilderImpl implements LocalBuilder {

    private final List<Iterable<BuildTarget>> callArguments;

    public LocalBuilderImpl() {
      callArguments = Lists.newArrayList();
    }

    public ImmutableList<Iterable<BuildTarget>> getCallArguments() {
      return ImmutableList.copyOf(callArguments);
    }

    @Override
    public int buildLocallyAndReturnExitCode(Iterable<BuildTarget> targetsToBuild)
        throws IOException, InterruptedException {
      callArguments.add(targetsToBuild);
      return 0;
    }
  }
}
