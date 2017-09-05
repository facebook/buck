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

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalFsContentsProviderTest {
  private static final long FUTURE_GET_TIMEOUT_SECONDS = 2;
  private static final byte[] FILE_CONTENTS = "topspin".getBytes(Charsets.UTF_8);

  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private Path cacheRootDir;
  private BuildJobStateFileHashEntry entry;
  private Path targetAbsPath;

  @Before
  public void setUp() {
    cacheRootDir = tempDir.getRoot().toPath();
    entry = new BuildJobStateFileHashEntry();
    entry.setSha1("1237987abc");
    targetAbsPath = cacheRootDir.resolve("topspin.file.txt");
  }

  @Test
  public void testGettingNonExistentFile()
      throws InterruptedException, IOException, ExecutionException, TimeoutException {
    try (LocalFsContentsProvider provider = new LocalFsContentsProvider(cacheRootDir)) {
      Assert.assertFalse(Files.isRegularFile(targetAbsPath));
      provider
          .materializeFileContentsAsync(entry, targetAbsPath)
          .get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertFalse(Files.isRegularFile(targetAbsPath));
    }
  }

  @Test
  public void testGettingExistentFile()
      throws InterruptedException, IOException, ExecutionException, TimeoutException {
    try (LocalFsContentsProvider provider = new LocalFsContentsProvider(cacheRootDir)) {
      Assert.assertFalse(Files.isRegularFile(targetAbsPath));

      Files.write(targetAbsPath, FILE_CONTENTS);
      Assert.assertTrue(Files.isRegularFile(targetAbsPath));
      provider.writeFileAndGetInputStream(entry, targetAbsPath);
      Assert.assertTrue(Files.isRegularFile(targetAbsPath));

      Path anotherAbsPath = cacheRootDir.resolve("slicespin.file.txt");
      Assert.assertFalse(Files.isRegularFile(anotherAbsPath));
      provider
          .materializeFileContentsAsync(entry, anotherAbsPath)
          .get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue(Files.isRegularFile(anotherAbsPath));
      Assert.assertThat(FILE_CONTENTS, Matchers.equalTo(Files.readAllBytes(anotherAbsPath)));
    }
  }
}
