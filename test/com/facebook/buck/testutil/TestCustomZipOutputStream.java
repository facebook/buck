/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.zip.CustomZipOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;

/**
 * A simple {@link CustomZipOutputStream} for testing purposes.
 */
public class TestCustomZipOutputStream extends CustomZipOutputStream {

  private ZipEntry currentEntry;
  private ByteArrayOutputStream currentEntryContent = new ByteArrayOutputStream();
  private List<ZipEntry> zipEntries = new ArrayList<>();
  private List<String> entriesContent = new ArrayList<>();

  public TestCustomZipOutputStream() {
    super(new ByteArrayOutputStream());
  }

  @Override
  protected void actuallyPutNextEntry(ZipEntry entry) throws IOException {
    currentEntry = entry;
    currentEntryContent.reset();
  }

  @Override
  protected void actuallyCloseEntry() throws IOException {
    zipEntries.add(currentEntry);
    entriesContent.add(currentEntryContent.toString());
  }

  @Override
  protected void actuallyWrite(byte[] b, int off, int len) throws IOException {
    currentEntryContent.write(b, off, len);
  }

  @Override
  protected void actuallyClose() throws IOException {
  }

  public List<ZipEntry> getZipEntries() {
    return zipEntries;
  }

  public List<String> getEntriesContent() {
    return entriesContent;
  }
}
