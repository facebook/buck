/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** Syntax node for a function call expression. */
public final class CallExpression extends Expression {

  private final Expression function;
  private final int lparenOffset;
  private final ImmutableList<Argument> arguments;
  private final int rparenOffset;

  private final int numPositionalArgs;

  CallExpression(
      FileLocations locs,
      Expression function,
      int lparenOffset,
      ImmutableList<Argument> arguments,
      int rparenOffset) {
    super(locs, Kind.CALL);
    this.function = Preconditions.checkNotNull(function);
    this.lparenOffset = lparenOffset;
    this.arguments = arguments;
    this.rparenOffset = rparenOffset;

    int n = 0;
    for (Argument arg : arguments) {
      if (arg instanceof Argument.Positional) {
        n++;
      }
    }
    this.numPositionalArgs = n;
  }

  /** Returns the function that is called. */
  public Expression getFunction() {
    return this.function;
  }

  /** Returns the number of arguments of type {@code Argument.Positional}. */
  public int getNumPositionalArguments() {
    return numPositionalArgs;
  }

  /** Returns the function arguments. */
  public ImmutableList<Argument> getArguments() {
    return arguments;
  }

  /** Star argument. */
  @Nullable
  public Argument.Star getStarArgument() {
    int i = arguments.size();
    if (i > 0 && arguments.get(i - 1) instanceof Argument.StarStar) {
      --i;
    }
    if (i > 0 && arguments.get(i - 1) instanceof Argument.Star) {
      return (Argument.Star) arguments.get(i - 1);
    }
    return null;
  }

  /** Star-star argument. */
  @Nullable
  public Argument.StarStar getStarStarArgument() {
    if (arguments.isEmpty()) {
      return null;
    }
    Argument lastArg = arguments.get(arguments.size() - 1);
    if (lastArg instanceof Argument.StarStar) {
      return (Argument.StarStar) lastArg;
    } else {
      return null;
    }
  }

  @Override
  public int getStartOffset() {
    return function.getStartOffset();
  }

  @Override
  public int getEndOffset() {
    return rparenOffset + 1;
  }

  public int getLparenOffset() {
    return lparenOffset;
  }

  public Location getLparenLocation() {
    return locs.getLocation(lparenOffset);
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder();
    buf.append(function);
    buf.append('(');
    ListExpression.appendNodes(buf, arguments);
    buf.append(')');
    return buf.toString();
  }

  @Override
  public void accept(NodeVisitor visitor) {
    visitor.visit(this);
  }
}
