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

package com.facebook.buck.util.bsd;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToObject;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.base.Charsets;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Before;
import org.junit.Test;

public class UnixArchiveTest {
  private Path tmp;

  @Before
  public void setUp() throws Exception {
    tmp = Files.createTempDirectory("junit-temp-path").toRealPath();
  }

  @Test
  public void testCheckingHeader() {
    byte[] bytes = "!<arch>\n..........".getBytes(Charsets.UTF_8);
    assertThat(
        UnixArchive.checkHeader(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)), equalTo(true));

    bytes = "UNEXPECTED_CONTENTS.......".getBytes(Charsets.UTF_8);
    assertThat(
        UnixArchive.checkHeader(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)),
        equalTo(false));
  }

  @Test
  public void testReadingArchive() throws IOException {
    Path path = tmp.resolve("test_archive.a");
    createArchiveAtPath(path);

    UnixArchive archive =
        new UnixArchive(
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(archive.getEntries().size(), equalTo(3));

    assertThat(archive.getEntries().get(0).getFileName(), equalToObject("__.SYMDEF SORTED"));
    assertThat(archive.getEntries().get(0).getFileModificationTimestamp(), equalTo(1463146035L));
    assertThat(archive.getEntries().get(0).getOwnerId(), equalTo(10727));
    assertThat(archive.getEntries().get(0).getGroupId(), equalTo(11706));
    assertThat(archive.getEntries().get(0).getFileMode(), equalTo(100644));

    assertThat(archive.getEntries().get(1).getFileName(), equalToObject("file1.txt"));
    assertThat(archive.getEntries().get(1).getFileModificationTimestamp(), equalTo(1463145840L));
    assertThat(archive.getEntries().get(1).getFileSize(), equalTo(16L));
    MappedByteBuffer buffer1 = archive.getMapForEntry(archive.getEntries().get(1));
    byte[] strBytes1 = new byte[(int) archive.getEntries().get(1).getFileSize()];
    buffer1.get(strBytes1, 0, (int) archive.getEntries().get(1).getFileSize());
    assertThat(new String(strBytes1), equalToObject("FILE1_CONTENTS\n\n"));

    assertThat(archive.getEntries().get(2).getFileName(), equalToObject("file2.txt"));
    assertThat(archive.getEntries().get(2).getFileModificationTimestamp(), equalTo(1463145848L));
    assertThat(archive.getEntries().get(2).getFileSize(), equalTo(32L));
    MappedByteBuffer buffer2 = archive.getMapForEntry(archive.getEntries().get(2));
    byte[] strBytes2 = new byte[(int) archive.getEntries().get(2).getFileSize()];
    buffer2.get(strBytes2, 0, (int) archive.getEntries().get(2).getFileSize());
    assertThat(new String(strBytes2), equalToObject("FILE2_CONTENTS_FILE2_CONTENTS\n\n\n"));

    archive.close();
  }

  @Test
  public void testModifyingArchive() throws IOException {
    Path path = tmp.resolve("test_archive.a");
    createArchiveAtPath(path);

    UnixArchive archive =
        new UnixArchive(
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));

    MappedByteBuffer buffer = archive.getMapForEntry(archive.getEntries().get(1));
    byte[] bytes = new byte[(int) archive.getEntries().get(1).getFileSize()];
    buffer.get(bytes, 0, (int) archive.getEntries().get(1).getFileSize());
    assertThat(new String(bytes), equalToObject("FILE1_CONTENTS\n\n"));

    buffer.position(0);
    buffer.put("NEW_CONTENTS!!!!".getBytes(Charsets.UTF_8));

    archive.close();

    String updatedContents = new String(Files.readAllBytes(path));
    assertThat(updatedContents, not(containsString("FILE1_CONTENTS")));
    assertThat(updatedContents, containsString("NEW_CONTENTS!!!!"));
  }

  private void createArchiveAtPath(Path path) throws IOException {
    byte[] magic = {0x60, 0x0A};
    String contents = "!<arch>\n";

    contents += "#1/20           1463146035  10727 11706 100644  28        ";
    contents += new String(magic);
    contents +=
        "__.SYMDEF SORTED\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000";

    contents += "#1/20           1463145840  10727 11706 100644  36        ";
    contents += new String(magic);
    contents += "file1.txt\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000";
    contents += "FILE1_CONTENTS\n\n";

    contents += "#1/20           1463145848  10727 11706 100644  52        ";
    contents += new String(magic);
    contents += "file2.txt\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000";
    contents += "FILE2_CONTENTS_FILE2_CONTENTS\n\n\n";

    FileWriter writer = new FileWriter(path.toFile());
    writer.write(contents);
    writer.close();
  }

  @Test
  public void testReadingStringDoesNotBreakPositionAndLimit() throws Exception {
    byte[] bytes = {66, 66, 66, 66, 66, 66, 66, 66, 66};
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    buffer.limit(buffer.capacity() - 4);

    UnixArchive.readStringWithLength(buffer, 3, StandardCharsets.UTF_8.newDecoder());

    assertThat(buffer.limit(), equalTo(buffer.capacity() - 4));
  }
}
