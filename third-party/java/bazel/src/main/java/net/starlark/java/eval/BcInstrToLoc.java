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

import com.google.common.base.Preconditions;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;

/** Map instruction to location. */
class BcInstrToLoc {
  private final FileLocations fileLocations;
  /** (instruction, offset) pairs */
  private final int[] instrToOffset;

  BcInstrToLoc(FileLocations fileLocations, int[] instrToOffset) {
    Preconditions.checkArgument(instrToOffset.length % 2 == 0);
    this.fileLocations = fileLocations;
    this.instrToOffset = instrToOffset;
  }

  Location locationAt(int ip) {
    // Could use binary search here, but this code is on cold path
    for (int i = 0; i < instrToOffset.length / 2; ++i) {
      if (instrToOffset[i * 2] == ip) {
        return fileLocations.getLocation(instrToOffset[i * 2 + 1]);
      }
    }

    throw new IllegalArgumentException("no offset at " + ip);
  }

  static class Builder {
    private final FileLocations fileLocations;
    /** Alternating (instr, offset). */
    private IntArrayBuilder instrToOffset = new IntArrayBuilder();

    Builder(FileLocations fileLocations) {
      this.fileLocations = fileLocations;
    }

    void reset(int ip) {
      while (!instrToOffset.isEmpty() && (instrToOffset.get(instrToOffset.size() - 2) >= ip)) {
        instrToOffset.pop();
        instrToOffset.pop();
      }
    }

    public void add(int ip, int offset) {
      if (!instrToOffset.isEmpty()) {
        Preconditions.checkArgument(ip > instrToOffset.get(instrToOffset.size() - 2));
      }

      instrToOffset.add(ip);
      instrToOffset.add(offset);
    }

    BcInstrToLoc build() {
      return new BcInstrToLoc(fileLocations, instrToOffset.buildArray());
    }
  }
}
