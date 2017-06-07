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
package com.facebook.buck.testutil;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

public class FileHashEntryMatcher extends BaseMatcher<BuildJobStateFileHashEntry> {
  private String path;
  private boolean isDirectory;

  public FileHashEntryMatcher(String path, boolean isDirectory) {
    this.path = path;
    this.isDirectory = isDirectory;
  }

  @Override
  public boolean matches(Object o) {
    if (!(o instanceof BuildJobStateFileHashEntry)) {
      return false;
    }

    BuildJobStateFileHashEntry entry = (BuildJobStateFileHashEntry) o;
    return entry.isDirectory == isDirectory && entry.getPath().getPath().equals(path);
  }

  @Override
  public void describeTo(Description description) {
    description.appendText(
        String.format("%s entry for path [%s]", (isDirectory ? "directory" : "file"), path));
  }
}
