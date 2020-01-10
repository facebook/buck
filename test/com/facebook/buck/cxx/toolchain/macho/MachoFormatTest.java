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

package com.facebook.buck.cxx.toolchain.macho;

import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieNode;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieWriter;
import com.facebook.buck.cxx.toolchain.objectfile.MachoSymTabCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoSymTabCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.junit.Before;
import org.junit.Test;

public class MachoFormatTest {
  private Path testDataDir;

  @Before
  public void setUp() throws IOException {
    testDataDir = TestDataHelper.getTestDataDirectory(this);
  }

  private Path getHelloLibDylibPath() {
    return testDataDir.resolve("samples").resolve("libHelloLib.dylib");
  }

  private Path getGlogDylibPath() {
    return testDataDir.resolve("samples").resolve("libglog-stripped.dylib");
  }

  private FileChannel openHelloLibDylibReadOnly() throws IOException {
    Path dylibFilePath = getHelloLibDylibPath();
    return FileChannel.open(dylibFilePath, StandardOpenOption.READ);
  }

  private MappedByteBuffer helloLibDylibByteBufferReadOnly() throws IOException {
    FileChannel dylibFileChannel = openHelloLibDylibReadOnly();
    return dylibFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, dylibFileChannel.size());
  }

  private MappedByteBuffer glogDylibByteBufferReadOnly() throws IOException {
    Path dylibFilePath = getGlogDylibPath();
    FileChannel dylibFileChannel = FileChannel.open(dylibFilePath, StandardOpenOption.READ);
    return dylibFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, dylibFileChannel.size());
  }

  @Test
  public void testSymTabCommand() throws IOException {
    MappedByteBuffer dylibBuffer = helloLibDylibByteBufferReadOnly();
    Optional<MachoSymTabCommand> maybeSymTabCommand = MachoSymTabCommandReader.read(dylibBuffer);
    assertTrue(maybeSymTabCommand.isPresent());

    MachoSymTabCommand symTabCommand = maybeSymTabCommand.get();
    assertThat(symTabCommand.getCommand(), equalTo(Machos.LC_SYMTAB));
    assertThat(symTabCommand.getCommandSize(), equalTo(24));
    assertThat(symTabCommand.getSymbolTableOffset(), equalTo(8288));
    assertThat(symTabCommand.getNumberOfSymbolTableEntries(), equalTo(16));
    assertThat(symTabCommand.getStringTableOffset(), equalTo(8560));
    assertThat(symTabCommand.getStringTableSize(), equalTo(280));
  }

  @Test
  public void testDyldInfoCommand() throws IOException {
    MappedByteBuffer dylibBuffer = helloLibDylibByteBufferReadOnly();
    Optional<MachoDyldInfoCommand> maybeDyldInfoCommand =
        MachoDyldInfoCommandReader.read(dylibBuffer);
    assertTrue(maybeDyldInfoCommand.isPresent());

    MachoDyldInfoCommand dyldInfoCommand = maybeDyldInfoCommand.get();
    assertThat(dyldInfoCommand.getCommand(), equalTo(Machos.LC_DYLD_INFO_ONLY));
    assertThat(dyldInfoCommand.getCommandSize(), equalTo(48));
    assertThat(dyldInfoCommand.getRebaseInfoOffset(), equalTo(8192));
    assertThat(dyldInfoCommand.getRebaseInfoSize(), equalTo(8));
    assertThat(dyldInfoCommand.getBindInfoOffset(), equalTo(8200));
    assertThat(dyldInfoCommand.getBindInfoSize(), equalTo(24));
    assertThat(dyldInfoCommand.getWeakBindInfoOffset(), equalTo(0));
    assertThat(dyldInfoCommand.getWeakBindInfoSize(), equalTo(0));
    assertThat(dyldInfoCommand.getLazyBindInfoOffset(), equalTo(8224));
    assertThat(dyldInfoCommand.getLazyBindInfoSize(), equalTo(16));
    assertThat(dyldInfoCommand.getExportInfoOffset(), equalTo(8240));
    assertThat(dyldInfoCommand.getExportInfoSize(), equalTo(40));
  }

  @Nonnull
  protected Optional<MachoExportTrieNode> readExportTrieFromHelloLib() throws IOException {
    return readExportTrieFromBuffer(helloLibDylibByteBufferReadOnly());
  }

  private Optional<MachoExportTrieNode> readExportTrieFromBuffer(MappedByteBuffer dylibBuffer) {
    Optional<MachoDyldInfoCommand> maybeDyldInfoCommand =
        MachoDyldInfoCommandReader.read(dylibBuffer);
    assertTrue(maybeDyldInfoCommand.isPresent());

    MachoDyldInfoCommand dyldInfoCommand = maybeDyldInfoCommand.get();

    dylibBuffer.position(dyldInfoCommand.getExportInfoOffset());
    ByteBuffer exportTrieByteBuffer = dylibBuffer.slice();
    exportTrieByteBuffer.limit(dyldInfoCommand.getExportInfoSize());

    return MachoExportTrieReader.read(exportTrieByteBuffer);
  }

  @Test
  public void testExportTrieReader() throws IOException {
    Optional<MachoExportTrieNode> maybeRoot = readExportTrieFromHelloLib();
    assertTrue(maybeRoot.isPresent());

    // The dylib contains two exported symbols at the following addresses:
    // - _hello: 0x00000F20
    // - _goodbye: 0x00000F50
    List<MachoExportTrieNode> nodesWithExportInfo = maybeRoot.get().collectNodesWithExportInfo();
    assertThat(nodesWithExportInfo.size(), equalTo(2));

    MachoExportTrieNode firstExportedSymbol = nodesWithExportInfo.get(0);
    assertTrue(firstExportedSymbol.getExportInfo().isPresent());
    assertThat(firstExportedSymbol.getExportInfo().get().address, equalTo(0xF20L));

    MachoExportTrieNode secondExportedSymbol = nodesWithExportInfo.get(1);
    assertTrue(secondExportedSymbol.getExportInfo().isPresent());
    assertThat(secondExportedSymbol.getExportInfo().get().address, equalTo(0xF50L));
  }

  @Test
  public void testGlogExportTrieReader() throws IOException {
    Optional<MachoExportTrieNode> maybeRoot =
        readExportTrieFromBuffer(glogDylibByteBufferReadOnly());
    assertTrue(maybeRoot.isPresent());

    // Verify that the glog dylib contains 213 exports
    List<MachoExportTrieNode> nodesWithExportInfo = maybeRoot.get().collectNodesWithExportInfo();
    assertThat(nodesWithExportInfo.size(), equalTo(213));
  }

  @Test
  public void testExportTrieWriter() throws IOException {
    Optional<MachoExportTrieNode> maybeRoot = readExportTrieFromHelloLib();
    assertTrue(maybeRoot.isPresent());

    List<MachoExportTrieNode> nodesWithExportInfo = maybeRoot.get().collectNodesWithExportInfo();
    assertThat(nodesWithExportInfo.size(), equalTo(2));
    nodesWithExportInfo.get(0).getExportInfo().get().address = 0xDEADBEEFL;
    nodesWithExportInfo.get(1).getExportInfo().get().address = 0xFEEDFACEL;

    ByteBuffer writeBuffer = ByteBuffer.allocate(256);
    MachoExportTrieWriter.write(maybeRoot.get(), writeBuffer);
    assertThat(writeBuffer.position(), not(equalTo(0)));

    writeBuffer.rewind();
    Optional<MachoExportTrieNode> maybeWrittenRoot = MachoExportTrieReader.read(writeBuffer);
    assertTrue(maybeWrittenRoot.isPresent());

    List<MachoExportTrieNode> writtenNodesWithExportInfo =
        maybeWrittenRoot.get().collectNodesWithExportInfo();
    assertThat(writtenNodesWithExportInfo.size(), equalTo(2));

    MachoExportTrieNode firstExportedSymbol = nodesWithExportInfo.get(0);
    assertTrue(firstExportedSymbol.getExportInfo().isPresent());
    assertThat(firstExportedSymbol.getExportInfo().get().address, equalTo(0xDEADBEEFL));

    MachoExportTrieNode secondExportedSymbol = nodesWithExportInfo.get(1);
    assertTrue(secondExportedSymbol.getExportInfo().isPresent());
    assertThat(secondExportedSymbol.getExportInfo().get().address, equalTo(0xFEEDFACEL));
  }
}
