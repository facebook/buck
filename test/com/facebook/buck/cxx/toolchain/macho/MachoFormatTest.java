/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cxx.toolchain.macho;

import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoSymTabCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoSymTabCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
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

  private FileChannel openHelloLibDylibReadOnly() throws IOException {
    Path dylibFilePath = getHelloLibDylibPath();
    return FileChannel.open(dylibFilePath, StandardOpenOption.READ);
  }

  private MappedByteBuffer helloLibDylibByteBufferReadOnly() throws IOException {
    FileChannel dylibFileChannel = openHelloLibDylibReadOnly();
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
}
