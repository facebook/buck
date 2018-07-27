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

package com.facebook.buck.cli;

import static org.junit.Assume.assumeFalse;

import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;
import org.junit.rules.ExternalResource;

/** Test rule to include when you'd like your test class to simulate using buckd. */
public class TestWithBuckd extends ExternalResource {

  private final TemporaryPaths temporaryPaths;

  public TestWithBuckd(TemporaryPaths temporaryPaths) {
    this.temporaryPaths = temporaryPaths;
  }

  @Override
  protected void before() throws IOException, InterruptedException {
    // In case root_restrict_files is enabled in /etc/watchmanconfig, assume
    // this is one of the entries so it doesn't give up.
    temporaryPaths.newFolder(".git");
    temporaryPaths.newFile(".arcconfig");
    // Create an empty watchman config file.
    temporaryPaths.newFile(".watchmanconfig");
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            ImmutableSet.of(temporaryPaths.getRoot()),
            getWatchmanEnv(),
            new TestConsole(),
            FakeClock.doNotCare(),
            Optional.empty());

    // We assume watchman has been installed and configured properly on the system, and that setting
    // up the watch is successful.
    assumeFalse(watchman == WatchmanFactory.NULL_WATCHMAN);
  }

  private static ImmutableMap<String, String> getWatchmanEnv() {
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    String systemPath = System.getenv("PATH");
    if (systemPath != null) {
      envBuilder.put("PATH", systemPath);
    }
    return envBuilder.build();
  }

  @Override
  protected void after() {
    Main.resetDaemon();
  }
}
