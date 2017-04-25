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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DaemonLifecycleManagerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private DaemonLifecycleManager daemonLifecycleManager;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
    daemonLifecycleManager = new DaemonLifecycleManager();
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated() throws IOException, InterruptedException {
    Object daemon =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build());

    assertEquals(
        "Daemon should not be replaced when config equal.",
        daemon,
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build()));

    assertNotEquals(
        "Daemon should be replaced when config not equal.",
        daemon,
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "someothervalue")))
                        .build())
                .setFilesystem(filesystem)
                .build()));
  }

  @Test
  public void whenAndroidNdkVersionChangesParserInvalidated()
      throws IOException, InterruptedException {

    BuckConfig buckConfig1 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "something")))
            .build();

    BuckConfig buckConfig2 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "different")))
            .build();

    Object daemon =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder().setBuckConfig(buckConfig1).setFilesystem(filesystem).build());

    assertNotEquals(
        "Daemon should be replaced when not equal.",
        daemon,
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder().setBuckConfig(buckConfig2).setFilesystem(filesystem).build()));
  }
}
