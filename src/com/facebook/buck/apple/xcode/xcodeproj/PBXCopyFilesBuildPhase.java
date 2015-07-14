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

public class PBXCopyFilesBuildPhase extends PBXBuildPhase {
  /**
   * The prefix path, this does not use SourceTreePath and build variables but rather some sort of
   * enum.
   */
  public enum Destination {
    ABSOLUTE(0),
    WRAPPER(1),
    EXECUTABLES(6),
    RESOURCES(7),
    FRAMEWORKS(10),
    SHARED_FRAMEWORKS(11),
    SHARED_SUPPORT(12),
    PLUGINS(13),
    JAVA_RESOURCES(15),
    PRODUCTS(16),
    ;

    private int value;

    public int getValue() {
      return value;
    }

    private Destination(int value) {
      this.value = value;
    }
  }

  /**
   * Spec for the destination folder.
   */
  private CopyFilePhaseDestinationSpec dstSubfolderSpec;

  public PBXCopyFilesBuildPhase(CopyFilePhaseDestinationSpec dstSubfolderSpec) {
    this.dstSubfolderSpec = dstSubfolderSpec;
  }

  public CopyFilePhaseDestinationSpec getDstSubfolderSpec() {
    return dstSubfolderSpec;
  }

  @Override
  public String isa() {
    return "PBXCopyFilesBuildPhase";
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);
    s.addField("dstSubfolderSpec", dstSubfolderSpec.getDestination().getValue());
    s.addField("dstPath", dstSubfolderSpec.getPath().or(""));
  }
}
