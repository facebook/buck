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

import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class MinionModeRunnerIntegrationTest {

  private static final StampedeId STAMPEDE_ID = ThriftCoordinatorServerIntegrationTest.STAMPEDE_ID;

  @Test
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    try (ThriftCoordinatorServer server = createServer()) {
      server.start();
      LocalBuilderImpl localBuilder = new LocalBuilderImpl();
      MinionModeRunner minion =
          new MinionModeRunner("localhost", server.getPort(), localBuilder, STAMPEDE_ID);
      int exitCode = minion.runAndReturnExitCode();
      Assert.assertEquals(0, exitCode);
      Assert.assertEquals(3, localBuilder.getCallArguments().size());
      int lastBuildIndex = localBuilder.getCallArguments().size() - 1;
      Assert.assertEquals(
          BuildTargetsQueueTest.TARGET_NAME,
          localBuilder.getCallArguments().get(lastBuildIndex).get(0));
    }
  }

  private ThriftCoordinatorServer createServer() throws NoSuchBuildTargetException, IOException {
    BuildTargetsQueue queue = BuildTargetsQueueTest.createDiamondDependencyQueue();
    return ThriftCoordinatorServerIntegrationTest.createServerOnRandomPort(queue);
  }

  public static class LocalBuilderImpl implements LocalBuilder {

    private final List<List<String>> callArguments;

    public LocalBuilderImpl() {
      callArguments = new ArrayList<>();
    }

    public List<List<String>> getCallArguments() {
      return callArguments;
    }

    @Override
    public int buildLocallyAndReturnExitCode(Iterable<String> targetsToBuild)
        throws IOException, InterruptedException {
      callArguments.add(ImmutableList.copyOf(targetsToBuild));
      return 0;
    }
  }
}
