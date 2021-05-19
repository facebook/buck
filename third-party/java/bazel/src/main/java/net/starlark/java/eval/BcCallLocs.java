package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;

/** Locations of call expression. */
class BcCallLocs {

  private final Location lparentLocation;
  private final int starOffset;
  private final int starStarOffset;

  private BcCallLocs(Location lparentLocation, int starOffset, int starStarOffset) {
    this.lparentLocation = lparentLocation;
    this.starOffset = starOffset;
    this.starStarOffset = starStarOffset;
  }

  static BcCallLocs forExpression(CallExpression callExpression) {
    Argument.Star starArgument = callExpression.getStarArgument();
    Argument.StarStar starStarArgument = callExpression.getStarStarArgument();
    int starOffset = starArgument != null ? starArgument.getStartOffset() : -1;
    int starStarOffset = starStarArgument != null ? starStarArgument.getStartOffset() : -1;
    return new BcCallLocs(callExpression.getLparenLocation(), starOffset, starStarOffset);
  }

  Location getLparentLocation() {
    return lparentLocation;
  }

  Location starLocation(FileLocations fileLocations) {
    Preconditions.checkState(starOffset >= 0);
    return fileLocations.getLocation(starOffset);
  }

  Location starStarLocation(FileLocations fileLocations) {
    Preconditions.checkState(starStarOffset >= 0);
    return fileLocations.getLocation(starStarOffset);
  }

  @Override
  public String toString() {
    return BcCallLocs.class.getSimpleName();
  }
}
