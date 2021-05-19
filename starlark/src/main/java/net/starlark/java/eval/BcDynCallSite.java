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
import net.starlark.java.syntax.Location;

/** Call site for {@link BcInstrOpcode#CALL} instruction. */
class BcDynCallSite {
  final BcCallLocs callLocs;
  final Location lparenLocation;
  private final StarlarkCallableLinkSig linkSig;

  BcDynCallSite(BcCallLocs callLocs, StarlarkCallableLinkSig linkSig) {
    this.callLocs = callLocs;
    this.lparenLocation = callLocs.getLparentLocation();
    this.linkSig = linkSig;
  }

  @Nullable private StarlarkCallableLinked linkedCache;

  Object call(
      StarlarkCallable callable,
      StarlarkThread thread,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> kwargs)
      throws InterruptedException, EvalException {
    if (callable instanceof BuiltinFunction) {
      // Skip caching for builtin functions for two reasons:
      // * linking does not really link anything for builtin functions
      // * when `CALL` instruction is used for builtin (as opposed to `CALL_LINKED`)
      //     it is likely instance call like `x.append`, so a function is a fresh object,
      //     and it will be cache miss anyway
      BuiltinFunction builtinFunction = (BuiltinFunction) callable;
      return builtinFunction.linkAndCall(linkSig, thread, args, starArgs, kwargs);
    }

    StarlarkCallableLinked linked = this.linkedCache;
    if (linked == null || linked.orig != callable) {
      this.linkedCache = linked = callable.linkCall(linkSig);
    }
    return linked.callLinked(thread, args, starArgs, kwargs);
  }
}
