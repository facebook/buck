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
import java.util.Optional;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MultiSourceContentsProviderTest {

  private static final byte[] FILE_CONTENTS = "cool contents".getBytes(Charsets.UTF_8);

  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private FileContentsProvider mockProvider;

  @Before
  public void setUp() {
    mockProvider = EasyMock.createMock(FileContentsProvider.class);
  }

  @Test
  public void inlineContentProviderTakesPrecedence() throws InterruptedException, IOException {
    EasyMock.replay(mockProvider);
    MultiSourceContentsProvider provider =
        new MultiSourceContentsProvider(mockProvider, Optional.empty());
    BuildJobStateFileHashEntry entry = new BuildJobStateFileHashEntry();
    entry.setHashCode("1234");
    entry.setContents(FILE_CONTENTS);
    Path targetAbsPath = tempDir.getRoot().toPath().resolve("my_file.txt");
    Assert.assertFalse(Files.isRegularFile(targetAbsPath));
    provider.materializeFileContents(entry, targetAbsPath);
    Assert.assertTrue(Files.isRegularFile(targetAbsPath));
    Assert.assertThat(Files.readAllBytes(targetAbsPath), Matchers.equalTo(FILE_CONTENTS));
    EasyMock.verify(mockProvider);
  }

  @Test
  public void serverIsUsedWhenInlineIsMissing() throws InterruptedException, IOException {
    BuildJobStateFileHashEntry entry = new BuildJobStateFileHashEntry();
    entry.setHashCode("1234");
    Path targetAbsPath = tempDir.getRoot().toPath().resolve("my_file.txt");
    EasyMock.expect(
            mockProvider.materializeFileContents(EasyMock.eq(entry), EasyMock.eq(targetAbsPath)))
        .andReturn(true)
        .once();
    EasyMock.replay(mockProvider);

    MultiSourceContentsProvider provider =
        new MultiSourceContentsProvider(mockProvider, Optional.empty());
    Assert.assertFalse(Files.isRegularFile(targetAbsPath));
    provider.materializeFileContents(entry, targetAbsPath);
    EasyMock.verify(mockProvider);
  }
}
