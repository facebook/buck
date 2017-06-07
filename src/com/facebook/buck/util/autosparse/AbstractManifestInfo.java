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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import java.util.EnumSet;
import org.immutables.value.Value;

/**
 * Track information on a file entry in a version control manifest.
 *
 * <p>Currently only the hash value (opaque hex value, unique per revision of a file) and file flags
 * (is it a link, executable flag set) are tracked.
 */
@Value.Immutable(copy = false)
@BuckStyleTuple
public abstract class AbstractManifestInfo {
  enum FileFlags {
    EXECUTABLE,
    LINK,
  }

  public abstract String getHash();

  abstract EnumSet<FileFlags> getFlags();

  public static ManifestInfo of(String hash, String flagsString) {
    EnumSet<FileFlags> flags = EnumSet.noneOf(FileFlags.class);
    if (flagsString.contains("x")) {
      flags.add(FileFlags.EXECUTABLE);
    }
    if (flagsString.contains("l")) {
      flags.add(FileFlags.LINK);
    }
    return ManifestInfo.of(hash, flags);
  }

  @Value.Lazy
  public boolean isExecutable() {
    return getFlags().contains(FileFlags.EXECUTABLE);
  }

  @Value.Lazy
  public boolean isLink() {
    return getFlags().contains(FileFlags.LINK);
  }

  @Value.Lazy
  public String getMode() {
    return isExecutable() ? "755" : "644";
  }
}
