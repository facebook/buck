// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import static com.android.tools.r8.dex.Constants.VDEX_NUMBER_OF_DEX_FILES_OFFSET;

import com.android.tools.r8.errors.CompilationError;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

public class VDexFileReader {

  private VDexFile file;
  private List<byte[]> dexFiles = new ArrayList<>();

  public void close() {
    file = null;
    dexFiles = ImmutableList.of();
  }

  public VDexFileReader(VDexFile file) {
    this.file = file;
    file.setByteOrder();
    parseDexFiles();
  }

  public List<byte[]> getDexFiles() {
    return dexFiles;
  }

  private void parseDexFiles() {
    file.position(VDEX_NUMBER_OF_DEX_FILES_OFFSET);
    int numberOfDexFiles = file.getUint();
    int dexSize = file.getUint();
    int verifierDepsSize = file.getUint();
    int quickeningInfoSize = file.getUint();

    int offset = VDexFile.firstDexOffset(numberOfDexFiles);
    int totalDexSize = 0;
    for (int i = 0; i < numberOfDexFiles; i++) {
      int size = file.getUint(offset + Constants.FILE_SIZE_OFFSET);
      file.position(offset);
      dexFiles.add(file.getByteArray(size));
      totalDexSize += size;
      offset += size;
    }
    if (totalDexSize != dexSize) {
      throw new CompilationError("Invalid vdex file. Mismatch in total dex files size");
    }
  }
}
