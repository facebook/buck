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

package com.facebook.buck.zip;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.zip.Deflater;

@RunWith(Parameterized.class)
public class CustomZipOutputStreamTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[]{Deflater.NO_COMPRESSION},
        new Object[]{Deflater.BEST_COMPRESSION},
        new Object[]{Deflater.BEST_SPEED});
  }

  @Parameterized.Parameter
  public Integer compressionLevel;

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void differentCompressionLevelsShouldWork() throws Exception {
    Path zipfile = tmp.getRoot().resolve("zipfile.zip");
    String name = "name";
    byte[] contents = "contents".getBytes(Charsets.UTF_8);
    OutputStream out = Files.newOutputStream(zipfile);
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      CustomZipEntry entry = new CustomZipEntry(name);
      entry.setCompressionLevel(compressionLevel);
      entry.setTime(0);
      zip.putNextEntry(entry);
      try (InputStream stream = new ByteArrayInputStream(contents)) {
        ByteStreams.copy(stream, zip);
      }
      zip.closeEntry();
    }
    try (com.facebook.buck.testutil.Zip zip = new com.facebook.buck.testutil.Zip(zipfile, false)) {
      assertThat(zip.readFully(name), is(equalTo(contents)));
    }
  }
}
