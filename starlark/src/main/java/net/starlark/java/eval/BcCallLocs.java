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
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;

/** Locations of call expression. */
class BcCallLocs {

  @Nullable private final ImmutableList<BcWriter.LocOffset> starLocs;
  @Nullable private final ImmutableList<BcWriter.LocOffset> starStarLocs;

  public BcCallLocs(
      @Nullable ImmutableList<BcWriter.LocOffset> starLocs,
      @Nullable ImmutableList<BcWriter.LocOffset> starStarLocs) {
    this.starLocs = starLocs;
    this.starStarLocs = starStarLocs;
  }

  private static final BcCallLocs NULLS = new BcCallLocs(null, null);

  private static BcCallLocs of(
      @Nullable ImmutableList<BcWriter.LocOffset> starLoc,
      @Nullable ImmutableList<BcWriter.LocOffset> starStarLoc) {
    if (starLoc == null && starStarLoc == null) {
      return NULLS;
    } else {
      return new BcCallLocs(starLoc, starStarLoc);
    }
  }

  static BcCallLocs forExpression(BcCompiler compiler, CallExpression callExpression) {
    Argument.Star starArgument = callExpression.getStarArgument();
    Argument.StarStar starStarArgument = callExpression.getStarStarArgument();
    ImmutableList<BcWriter.LocOffset> starLoc =
        starArgument != null ? compiler.nodeToLocOffset(starArgument) : null;
    ImmutableList<BcWriter.LocOffset> starStarLoc =
        starStarArgument != null ? compiler.nodeToLocOffset(starStarArgument) : null;
    return of(starLoc, starStarLoc);
  }

  ImmutableList<StarlarkThread.CallStackEntry> starLocation() {
    Preconditions.checkState(starLocs != null);
    return starLocs.stream()
        .map(BcWriter.LocOffset::toCallStackEntry)
        .collect(ImmutableList.toImmutableList());
  }

  ImmutableList<StarlarkThread.CallStackEntry> starStarLocation() {
    Preconditions.checkState(starStarLocs != null);
    return starStarLocs.stream()
        .map(BcWriter.LocOffset::toCallStackEntry)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public String toString() {
    return BcCallLocs.class.getSimpleName();
  }
}
