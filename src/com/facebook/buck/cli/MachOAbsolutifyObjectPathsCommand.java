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
package com.facebook.buck.cli;

import com.facebook.buck.charset.NulTerminatedCharsetDecoder;
import com.facebook.buck.macho.ObjectPathsAbsolutifier;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public class MachOAbsolutifyObjectPathsCommand extends MachOAbstractCommand {

  @Override
  protected int invokeWithParams(CommandRunnerParams params) throws IOException {
    try (RandomAccessFile file = new RandomAccessFile(getOutput().toFile(), "rw")) {
      NulTerminatedCharsetDecoder decoder =
          new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
      ObjectPathsAbsolutifier updater =
          new ObjectPathsAbsolutifier(
              file,
              getOldCompDir(),
              getUpdatedCompDir(),
              params.getCell().getFilesystem(),
              params.getCell().getKnownRoots(),
              decoder);
      updater.updatePaths();
    }
    return 0;
  }

  @Override
  public String getShortDescription() {
    return "absolutifies the paths inside Mach O binary and satellite object files";
  }
}
