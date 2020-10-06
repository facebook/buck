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

package com.facebook.buck.features.project.intellij.targetinfo;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class HashFileTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void testWriteAndGet_succeedWithStringSerializer() throws Exception {
    File temporaryFile = temp.newFile();
    HashFile<String, String> file =
        new HashFile(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, temporaryFile.toPath());

    Map<String, String> data = new HashMap<>();
    data.put("First", "One");
    data.put("Second", "Two");
    data.put("Third", "Three");
    data.put("Fourth", "Four");

    file.write(data);

    assertEquals("One", file.get("First"));
    assertEquals("Two", file.get("Second"));
    assertEquals("Three", file.get("Third"));
    assertEquals("Four", file.get("Fourth"));
    assertNull(file.get("Random"));
  }

  @Test
  public void testWriteAndGet_stringsWithSameHashcode() throws Exception {
    File temporaryFile = temp.newFile();
    HashFile<String, String> file =
        new HashFile<>(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, temporaryFile.toPath());

    // Sanity check. Because of the implementation of String.hashCode(), all of these strings
    // should have the same hashcode.
    assertEquals("AaAa".hashCode(), "BBBB".hashCode());
    assertEquals("BBBB".hashCode(), "AaBB".hashCode());
    assertEquals("AaBB".hashCode(), "BBAa".hashCode());

    Map<String, String> data = new HashMap<>();
    data.put("AaAa", "First");
    data.put("BBBB", "Second");
    data.put("AaBB", "Third");
    data.put("BBAa", "Fourth");

    file.write(data);

    assertEquals("First", file.get("AaAa"));
    assertEquals("Second", file.get("BBBB"));
    assertEquals("Third", file.get("AaBB"));
    assertEquals("Fourth", file.get("BBAa"));
  }

  @Test(expected = FileNotFoundException.class)
  public void testGet_FailsSanelyWhenNoFile() throws Exception {
    File temporaryFile = new File(temp.getRoot(), "file.bin");
    HashFile<String, String> file =
        new HashFile<>(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, temporaryFile.toPath());
    file.get("Nothing");
  }

  public void testGet_FailsSanelyWhenVersionIsWrong() throws Exception {
    // Write out a file with the correct version
    File temporaryFile = temp.newFile();
    HashFile<String, String> file =
        new HashFile<>(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, temporaryFile.toPath());
    Map<String, String> data = new HashMap<>();
    data.put("Foo", "Bar");
    file.write(data);

    // alter the first byte of the file to an unlikely version
    try (RandomAccessFile ra = new RandomAccessFile(temporaryFile, "rw")) {
      ra.write(0xff);
    }

    exception.expect(IOException.class);
    exception.expectMessage("version");

    file.get("Foo");
  }

  @Test
  public void testWrite_overwritesExistingFile() throws Exception {
    File temporaryFile = temp.newFile();
    HashFile<String, String> file =
        new HashFile<>(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, temporaryFile.toPath());
    Map<String, String> data = new HashMap<>();
    data.put("Old", "Thing");
    file.write(data);

    data.remove("Old");
    data.put("New", "Gniht");
    file.write(data);

    assertEquals("Gniht", data.get("New"));
    assertNull(data.get("Old"));
  }

  @Test
  public void testWrite_emptyMapIsOk() throws Exception {
    File temporaryFile = temp.newFile();
    HashFile<String, String> file =
        new HashFile<>(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, temporaryFile.toPath());
    Map<String, String> data = new HashMap<>();
    file.write(data);
    assertNull(data.get("Anything"));
  }

  @Test
  public void testWrite_customSerializers() throws Exception {
    File temporaryFile = temp.newFile();
    HashFile<String, Widget> file =
        new HashFile<>(HashFile.STRING_SERIALIZER, new WidgetSerializer(), temporaryFile.toPath());
    Map<String, Widget> data = new HashMap<>();
    Widget widget = new Widget();
    widget.name = "A widget!";
    widget.type = "Class 1 widget";
    data.put("Hello", widget);
    file.write(data);

    widget = data.get("Hello");
    assertEquals("A widget!", widget.name);
    assertEquals("Class 1 widget", widget.type);
  }

  private static class WidgetSerializer implements HashFile.Serializer<Widget> {
    @Override
    public void serialize(Widget value, DataOutput output) throws IOException {
      output.writeUTF(value.name);
      output.writeUTF(value.type);
    }

    @Override
    public Widget deserialize(DataInput input) throws IOException {
      Widget w = new Widget();
      w.name = input.readUTF();
      w.type = input.readUTF();
      return w;
    }
  }

  private static class Widget {
    String name;
    String type;
  }
}
