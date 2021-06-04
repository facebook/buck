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
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;

/** Locations of call expression. */
class BcCallLocs {

  private final int starOffset;
  private final int starStarOffset;

  private BcCallLocs(int starOffset, int starStarOffset) {
    this.starOffset = starOffset;
    this.starStarOffset = starStarOffset;
  }

  private static final BcCallLocs NULLS = new BcCallLocs(-1, -1);

  static BcCallLocs forExpression(CallExpression callExpression) {
    Argument.Star starArgument = callExpression.getStarArgument();
    Argument.StarStar starStarArgument = callExpression.getStarStarArgument();
    int starOffset = starArgument != null ? starArgument.getStartOffset() : -1;
    int starStarOffset = starStarArgument != null ? starStarArgument.getStartOffset() : -1;
    if (starOffset < 0 && starStarOffset < 0) {
      return NULLS;
    } else {
      return new BcCallLocs(starOffset, starStarOffset);
    }
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
