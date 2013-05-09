/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SplitZipStepTest {
  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Test
  public void testMetaList() throws IOException {
    File outJar = tempDir.newFile("test.jar");
    ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(outJar)));
    Map<String, String> fileToClassName = ImmutableMap.of(
        "com/facebook/foo.class", "com.facebook.foo",
        "bar.class", "bar");
    try {
      for (String entry : fileToClassName.keySet()) {
        zipOut.putNextEntry(new ZipEntry(entry));
        zipOut.write(new byte[] { 0 });
      }
    } finally {
      zipOut.close();
    }

    StringWriter stringWriter = new StringWriter();
    BufferedWriter writer = new BufferedWriter(stringWriter);
    try {
      SplitZipStep.writeMetaList(writer, ImmutableList.of(outJar));
    } finally {
      writer.close();
    }
    List<String> lines = CharStreams.readLines(new StringReader(stringWriter.toString()));
    assertEquals(1, lines.size());

    String line = Iterables.getFirst(lines, null);
    String[] data = line.split(" ");
    assertEquals(3, data.length);

    // Note that we cannot test data[1] (the hash) because zip files change their hash each
    // time they are written due to timestamps written into the file.
    assertEquals(SmartDexingStep.transformInputToDexOutput(outJar.getName()), data[0]);
    assertTrue(String.format("Unexpected class: %s", data[2]),
        fileToClassName.values().contains(data[2]));
  }

  @Test
  public void testClassFilePattern() {
    assertTrue(SplitZipStep.classFilePattern.matcher(
        "com/facebook/orca/threads/ParticipantInfo.class").matches());
    assertTrue(SplitZipStep.classFilePattern.matcher(
        "com/facebook/orca/threads/ParticipantInfo$1.class").matches());
  }
}
