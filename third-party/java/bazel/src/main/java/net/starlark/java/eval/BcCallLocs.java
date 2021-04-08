package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;

/** Locations of call expression. */
class BcCallLocs {
  private final FileLocations fileLocations;
  private final Location lparentLocation;
  private final int starOffset;
  private final int starStarOffset;

   private BcCallLocs(FileLocations fileLocations, Location lparentLocation, int starOffset,
       int starStarOffset) {
    this.fileLocations = fileLocations;
    this.lparentLocation = lparentLocation;
    this.starOffset = starOffset;
    this.starStarOffset = starStarOffset;
  }

  static BcCallLocs forExpression(CallExpression callExpression) {
    Argument.Star starArgument = callExpression.getStarArgument();
    Argument.StarStar starStarArgument = callExpression.getStarStarArgument();
    int starOffset = starArgument != null ? starArgument.getStartOffset() : -1;
    int starStarOffset = starStarArgument != null ? starStarArgument.getStartOffset() : -1;
    return new BcCallLocs(callExpression.getLocs(), callExpression.getLparenLocation(), starOffset, starStarOffset);
  }

  Location getLparentLocation() {
    return lparentLocation;
  }

  Location starLocation() {
    Preconditions.checkState(starOffset >= 0);
    return fileLocations.getLocation(starOffset);
  }

  Location starStarLocation() {
    Preconditions.checkState(starStarOffset >= 0);
    return fileLocations.getLocation(starStarOffset);
  }
}
