/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.watchman;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WatchmanTransportClientTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd initWatchman = new TestWithBuckd(tmp);

  private WatchmanClient watchmanTransportClient;

  @Before
  public void before() throws Exception {
    watchmanTransportClient =
        new WatchmanFactory()
            .build(
                ImmutableSet.of(tmp.getRoot()),
                EnvVariablesProvider.getSystemEnv(),
                new TestEventConsole(),
                FakeClock.doNotCare(),
                Optional.empty(),
                Optional.empty())
            .createClient();
  }

  @After
  public void after() throws Exception {
    if (watchmanTransportClient != null) {
      watchmanTransportClient.close();
    }
  }

  @Test
  public void error() throws Exception {
    WatchmanQueryFailedException exception =
        Assert.assertThrows(
            WatchmanQueryFailedException.class,
            () -> {
              watchmanTransportClient.queryWithTimeout(
                  60_000_000_000L,
                  60_000_000_000L,
                  WatchmanQuery.query(
                      tmp.getRoot().toString(),
                      Optional.empty(),
                      Optional.empty(),
                      Optional.of(ImmutableList.of("*.txt")),
                      ImmutableList.of("fgfg")));
            });
    Assert.assertTrue(exception.getMessage().contains("unknown field name 'fgfg'"));
  }
}
