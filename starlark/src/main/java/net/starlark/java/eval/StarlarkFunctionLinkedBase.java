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

import javax.annotation.Nullable;

/** Base class for all starlark functions (as opposed to builtin) linked functions. */
abstract class StarlarkFunctionLinkedBase extends StarlarkCallableLinked {
  protected StarlarkFunctionLinkedBase(StarlarkCallableLinkSig linkSig, StarlarkFunction fn) {
    super(linkSig, fn);
  }

  protected StarlarkFunction fn() {
    return (StarlarkFunction) orig;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object callLinked(
      StarlarkThread thread, Object[] args, Sequence<?> starArgs, Dict<?, ?> starStarArgs)
      throws EvalException, InterruptedException {
    StarlarkFunction fn = fn();
    StarlarkThread.Frame fr;
    Object[] locals;

    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.enter(StarlarkRuntimeStats.WhereWeAre.DEF_PREPARE_ARGS);
    }

    try {
      if (!thread.isRecursionAllowed() && thread.isRecursiveCall(fn)) {
        throw Starlark.errorf("function '%s' called recursively", fn.getName());
      }

      fr = thread.frame(0);
      locals = new Object[fn.compiled.slotCount];

      // Compute the effective parameter values
      // and update the corresponding variables.
      processArgs(thread.mutability(), args, starArgs, (Dict<Object, Object>) starStarArgs, locals);

      // Spill indicated locals to cells.
      for (int index : fn.cellIndices) {
        locals[index] = new StarlarkFunction.Cell(locals[index]);
      }

    } finally {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.leave();
      }
    }

    return BcEval.eval(thread, fr, fn, locals);
  }

  protected abstract void processArgs(
      Mutability mu,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<Object, Object> starStarArgs,
      Object[] locals)
      throws EvalException;
}
