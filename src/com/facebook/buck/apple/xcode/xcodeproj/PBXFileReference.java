/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

/**
 * Reference to a concrete file.
 */
public class PBXFileReference extends PBXReference {
  private static ImmutableMap<String, String> fileTypeToFileTypeIdentifiers =
      ImmutableMap.<String, String>builder()
          .put("h", "sourcecode.c.h")
          .put("hh", "sourcecode.cpp.h")
          .put("hpp", "sourcecode.cpp.h")
          .put("c", "sourcecode.c.c")
          .put("cc", "sourcecode.cpp.cpp")
          .put("cpp", "sourcecode.cpp.cpp")
          .put("a", "archive.ar")
          .put("m", "sourcecode.c.objc")
          .put("mm", "sourcecode.cpp.objcpp")
          .put("png", "image.png")
          .put("xcconfig", "text.xcconfig")
          .build();

  private Optional<String> lastKnownFileType;

  public PBXFileReference(String name, String path, SourceTree sourceTree) {
    super(name, path, sourceTree);

    // this is necessary to prevent O(n^2) behavior in xcode project loading
    String fileType = fileTypeToFileTypeIdentifiers.get(Files.getFileExtension(name));
    lastKnownFileType = Optional.fromNullable(fileType);
  }

  public Optional<String> getLastKnownFileType() {
    return lastKnownFileType;
  }

  public void setLastKnownFileType(Optional<String> lastKnownFileType) {
    this.lastKnownFileType = lastKnownFileType;
  }

  @Override
  public String isa() {
    return "PBXFileReference";
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    if (lastKnownFileType.isPresent()) {
      s.addField("lastKnownFileType", lastKnownFileType.get());
    }
  }
}
