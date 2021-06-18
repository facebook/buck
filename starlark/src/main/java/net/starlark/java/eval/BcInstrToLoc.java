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

package net.starlark.java.eval;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;

/** Map instruction to location. */
class BcInstrToLoc {
  private final String[] fnNames;
  private final FileLocations[] fileLocations;
  /** {@code *(instruction stackSize *(fnNamesIndex, fileLocationsIndex, fileLocationsOffset))}. */
  private final int[] instrToOffset;

  private BcInstrToLoc(String[] fnNames, FileLocations[] fileLocations, int[] instrToOffset) {
    this.fnNames = fnNames;
    this.fileLocations = fileLocations;
    this.instrToOffset = instrToOffset;
  }

  ImmutableList<StarlarkThread.CallStackEntry> locationAt(int ip) {
    int offset = 0;
    // Could use binary search here, but this code is on cold path
    while (offset != instrToOffset.length) {
      int nextIp = instrToOffset[offset++];
      int count = instrToOffset[offset++];
      if (ip == nextIp) {
        ImmutableList.Builder<StarlarkThread.CallStackEntry> locations =
            ImmutableList.builderWithExpectedSize(count);
        for (int i = 0; i != count; ++i) {
          String fnName = this.fnNames[instrToOffset[offset + 3 * i]];
          FileLocations fileLocations = this.fileLocations[instrToOffset[offset + 3 * i + 1]];
          Location loc = fileLocations.getLocation(instrToOffset[offset + 3 * i + 2]);
          locations.add(new StarlarkThread.CallStackEntry(fnName, loc));
        }
        return locations.build();
      }
      offset += count * 3;
    }
    throw new IllegalArgumentException("no offset at " + ip);
  }

  static class Builder {
    private final ArrayList<String> fnNames = new ArrayList<>();
    private final ArrayList<FileLocations> fileLocations = new ArrayList<>();
    private final IntArrayBuilder instrToOffset = new IntArrayBuilder();

    Builder() {}

    private int indexFileLocations(FileLocations fileLocations) {
      for (int i = 0; i < this.fileLocations.size(); i++) {
        FileLocations fileLocation = this.fileLocations.get(i);
        if (fileLocation == fileLocations) {
          return i;
        }
      }
      this.fileLocations.add(fileLocations);
      return this.fileLocations.size() - 1;
    }

    private int indexFnName(String fnName) {
      for (int i = 0; i < fnNames.size(); i++) {
        String next = fnNames.get(i);
        if (fnName.equals(next)) {
          return i;
        }
      }
      fnNames.add(fnName);
      return fnNames.size() - 1;
    }

    public void add(int ip, ImmutableList<BcWriter.LocOffset> locOffsets) {
      instrToOffset.add(ip);
      instrToOffset.add(locOffsets.size());
      for (BcWriter.LocOffset locOffset : locOffsets) {
        instrToOffset.add(indexFnName(locOffset.fnName));
        instrToOffset.add(indexFileLocations(locOffset.fileLocations));
        instrToOffset.add(locOffset.offset);
      }
    }

    BcInstrToLoc build() {
      return new BcInstrToLoc(
          fnNames.toArray(new String[0]),
          fileLocations.toArray(new FileLocations[0]),
          instrToOffset.buildArray());
    }
  }
}
