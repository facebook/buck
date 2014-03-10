/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testutil.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkInspector {

  private final ImmutableSet<String> apkFileEntries;

  public ApkInspector(File apkFile) throws IOException {
    final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    Enumeration<? extends ZipEntry> entries = new ZipFile(apkFile).entries();
    while (entries.hasMoreElements()) {
      builder.add(entries.nextElement().getName());
    }
    this.apkFileEntries = builder.build();
  }

  public void assertFileExists(String pathRelativeToRoot) {
    assertTrue(apkFileEntries.contains(pathRelativeToRoot));
  }

  public void assertFileDoesNotExist(String pathRelativeToRoot) {
    assertFalse(apkFileEntries.contains(pathRelativeToRoot));
  }
}
