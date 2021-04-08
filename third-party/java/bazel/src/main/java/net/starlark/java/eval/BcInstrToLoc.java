package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;

/** Map instruction to location. */
class BcInstrToLoc {
  private final FileLocations fileLocations;
  /** (instruction, offset) pairs */
  private final int[] instrToOffset;

  BcInstrToLoc(FileLocations fileLocations,
      int[] instrToOffset) {
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
