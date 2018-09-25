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
package com.facebook.buck.manifestservice;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Joiner;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ManifestServiceConfigTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private Clock clock;
  private BuckEventBus eventBus;
  private ListeningExecutorService executor;

  @Before
  public void setUp() {
    clock = EasyMock.createNiceMock(Clock.class);
    eventBus = EasyMock.createNiceMock(BuckEventBus.class);
    executor = EasyMock.createNiceMock(ListeningExecutorService.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCreatingFromEmptyConfig() throws IOException {
    Reader reader = new StringReader("");
    BuckConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    ManifestServiceConfig manifestConfig = new ManifestServiceConfig(buckConfig);
    manifestConfig.createManifestService(clock, eventBus, executor);
  }

  @Test
  public void testCorrectConfig() throws IOException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[manifestservice]",
                    "slb_server_pool=http://localhost:8080",
                    "slb_ping_endpoint=/ping"));
    BuckConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    ManifestServiceConfig manifestConfig = new ManifestServiceConfig(buckConfig);
    try (ManifestService service =
        manifestConfig.createManifestService(clock, eventBus, executor)) {
      Assert.assertNotNull(service);
    }
  }
}
