/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.event.listener;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteExecutionConsoleLineProviderTest {
  private String reSessionID = "reSessionID-FOO123";
  private TestStatsProvider statsProvider;
  private RemoteExecutionMetadata remoteExecutionMetadata;

  @Before
  public void setUp() {
    this.statsProvider = new TestStatsProvider();
    this.remoteExecutionMetadata =
        RemoteExecutionMetadata.newBuilder()
            .setReSessionId(RESessionID.newBuilder().setId(reSessionID).build())
            .build();
  }

  @Test
  public void testConsoleOutput() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);
    BuckConfig config = FakeBuckConfig.builder().build();
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(
            statsProvider, config.getView(RemoteExecutionConfig.class), remoteExecutionMetadata);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(3, lines.size());
    Assert.assertEquals("[RE] Metadata: Session ID=[reSessionID-FOO123]", lines.get(0));
    Assert.assertEquals(
        "[RE] Actions: Local=0 Remote=[wait=0 del=0 comp=0 upl=0 exec=0 dwl=0 suc=84 fail=0 cncl=0]",
        lines.get(1));
    Assert.assertEquals(
        "[RE] CAS: Upl=[Count:0 Size=0.00 bytes] Dwl=[Count:21 Size=42.00 bytes]", lines.get(2));
  }

  @Test
  public void testDebugFormatConsoleOutput() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                "[remoteexecution]", "debug_format_string_url=https://localhost/test?blah={id}")
            .build();
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(
            statsProvider, config.getView(RemoteExecutionConfig.class), remoteExecutionMetadata);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(3, lines.size());
    Assert.assertEquals(
        "[RE] Metadata: Session ID=[https://localhost/test?blah=reSessionID-FOO123]", lines.get(0));
    Assert.assertEquals(
        "[RE] Actions: Local=0 Remote=[wait=0 del=0 comp=0 upl=0 exec=0 dwl=0 suc=84 fail=0 cncl=0]",
        lines.get(1));
    Assert.assertEquals(
        "[RE] CAS: Upl=[Count:0 Size=0.00 bytes] Dwl=[Count:21 Size=42.00 bytes]", lines.get(2));
  }

  private static final class TestStatsProvider implements RemoteExecutionStatsProvider {
    public Map<State, Integer> actionsPerState = Maps.newHashMap();
    public int casDownloads = 0;
    public int casDownladedBytes = 0;

    public TestStatsProvider() {
      for (State state : State.values()) {
        actionsPerState.put(state, new Integer(0));
      }
    }

    @Override
    public ImmutableMap<State, Integer> getActionsPerState() {
      return ImmutableMap.copyOf(actionsPerState);
    }

    @Override
    public int getCasDownloads() {
      return casDownloads;
    }

    @Override
    public long getCasDownloadSizeBytes() {
      return casDownladedBytes;
    }

    @Override
    public int getCasUploads() {
      return 0;
    }

    @Override
    public long getCasUploadSizeBytes() {
      return 0;
    }

    @Override
    public int getTotalRulesBuilt() {
      return 0;
    }
  }
}
