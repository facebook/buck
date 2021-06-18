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
import java.util.ArrayList;
import javax.annotation.Nullable;
import net.starlark.java.annot.FnPurity;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.Expression;

/** Compile function calls. */
class BcCompilerForCall {
  private final BcCompiler compiler;

  BcCompilerForCall(BcCompiler compiler) {
    this.compiler = compiler;
  }

  /** Precompiled function and args. */
  private static class CallCompiledArguments {
    private final CallExpression callExpression;
    private final BcCompiler.CompileExpressionResultWithIr fn;
    private final StarlarkCallableLinkSig linkSig;
    private final BcCompiler.ListArgWithIr callArgs;
    private final BcCompiler.SlotOrNullWithIr star;
    private final BcCompiler.SlotOrNullWithIr starStar;

    CallCompiledArguments(
        CallExpression callExpression,
        BcCompiler.CompileExpressionResultWithIr fn,
        StarlarkCallableLinkSig linkSig,
        BcCompiler.ListArgWithIr callArgs,
        BcCompiler.SlotOrNullWithIr star,
        BcCompiler.SlotOrNullWithIr starStar) {
      this.callExpression = callExpression;
      this.fn = fn;
      this.linkSig = linkSig;
      this.callArgs = callArgs;
      this.star = star;
      this.starStar = starStar;
    }

    /** Write all compiled arguments to given IR object. */
    void addToIr(BcIr ir) {
      ir.addAll(fn.ir);
      ir.addAll(callArgs.ir);
      ir.addAll(star.ir);
      ir.addAll(starStar.ir);
    }

    /** Assert no IR produced when compiling this function arguments. */
    void assertNoIr() {
      Preconditions.checkState(fn.ir.isEmpty());
      Preconditions.checkState(callArgs.ir.isEmpty());
      Preconditions.checkState(star.ir.isEmpty());
      Preconditions.checkState(starStar.ir.isEmpty());
    }
  }

  private CallCompiledArguments compileCallArguments(CallExpression callExpression) {
    ArrayList<String> argNames = new ArrayList<>();
    ArrayList<Expression> regArgs = new ArrayList<>();
    Argument.Star star = null;
    Argument.StarStar starStar = null;
    for (Argument argument : callExpression.getArguments()) {
      if (argument instanceof Argument.Positional) {
        regArgs.add(argument.getValue());
      } else if (argument instanceof Argument.Keyword) {
        argNames.add(argument.getName());
        regArgs.add(argument.getValue());
      } else if (argument instanceof Argument.Star) {
        Preconditions.checkState(star == null);
        star = (Argument.Star) argument;
      } else if (argument instanceof Argument.StarStar) {
        Preconditions.checkState(starStar == null);
        starStar = (Argument.StarStar) argument;
      } else {
        throw new IllegalStateException();
      }
    }

    StarlarkCallableLinkSig linkSig =
        StarlarkCallableLinkSig.of(
            regArgs.size() - argNames.size(),
            argNames.toArray(ArraysForStarlark.EMPTY_STRING_ARRAY),
            star != null,
            starStar != null);

    BcCompiler.CompileExpressionResultWithIr function =
        compiler.compileExpression(callExpression.getFunction());

    BcCompiler.ListArgWithIr regArgsList = compiler.compileExpressionList(regArgs);

    BcCompiler.SlotOrNullWithIr starSlot =
        compiler.compileExpressionOrNull(star != null ? star.getValue() : null);
    BcCompiler.SlotOrNullWithIr starStarSlot =
        compiler.compileExpressionOrNull(starStar != null ? starStar.getValue() : null);

    return new CallCompiledArguments(
        callExpression, function, linkSig, regArgsList, starSlot, starStarSlot);
  }

  @Nullable
  private BcCompiler.CompileExpressionResult tryCompileSpecSafeInline(
      BcIr ir,
      StarlarkCallable callable,
      CallCompiledArguments compiledArguments,
      BcIrLocalOrAny result) {
    StarlarkCallableLinkSig linkSig = compiledArguments.linkSig;
    BcIrListArg args = compiledArguments.callArgs.listArg;
    CallExpression callExpression = compiledArguments.callExpression;

    boolean functionIsSpeculativeSafe =
        callable instanceof BuiltinFunction
            && ((BuiltinFunction) callable).purity() == FnPurity.SPEC_SAFE;
    if (functionIsSpeculativeSafe && !linkSig.hasStars() && args.allConstantsImmutable()) {
      try {
        Object specCallResult =
            callable.linkAndCall(linkSig, compiler.thread, args.data(), null, null);
        if (Starlark.isImmutable(specCallResult)) {
          compiledArguments.assertNoIr();
          return compiler.compileConstantTo(ir, callExpression, specCallResult, result);
        }
      } catch (EvalException | InterruptedException e) {
        // ignore
      }
    }

    return null;
  }

  @Nullable
  private BcCompiler.CompileExpressionResult tryCompileCallCached(
      BcIr ir,
      StarlarkCallable callable,
      CallCompiledArguments compiledArguments,
      BcIrLocalOrAny result) {
    StarlarkCallableLinkSig linkSig = compiledArguments.linkSig;
    BcIrListArg regArgsResult = compiledArguments.callArgs.listArg;

    if (regArgsResult.allConstantsImmutable()
        && !linkSig.hasStars()
        && callable instanceof StarlarkFunction
        && callable.isImmutable()) {
      compiledArguments.addToIr(ir);

      BcIrSlot.AnyLocal resultLocal = result.makeLocal("call");

      ir.add(
          new BcIrInstr.CallCached(
              compiler.nodeToLocOffset(compiledArguments.callExpression),
              new BcCallCached((StarlarkFunction) callable, linkSig, regArgsResult.data()),
              resultLocal));

      return new BcCompiler.CompileExpressionResult(resultLocal);
    }

    return null;
  }

  @Nullable
  private BcCompiler.CompileExpressionResult tryInlineConst(
      BcIr ir,
      StarlarkCallable callable,
      CallCompiledArguments compiledArguments,
      BcIrLocalOrAny result) {

    StarlarkCallableLinkSig linkSig = compiledArguments.linkSig;
    CallExpression callExpression = compiledArguments.callExpression;

    // Only inline no-argument calls to no-parameter functions, otherwise
    // it's quite hard to correctly detect that function call won't fail at runtime.
    // Consider this example:
    // ```
    // def bar(a):
    //   # This function could be inalienable as constant
    //   return None
    // def foo():
    //   # If this call inlined as constant,
    //   # we need to report `x` accessed before initialization
    //   bar(x)
    //   x = 1
    // ```
    if (callable instanceof StarlarkFunction && linkSig == StarlarkCallableLinkSig.positional(0)) {
      Object constResult = ((StarlarkFunction) callable).returnsConst();
      if (constResult != null && ((StarlarkFunction) callable).getParameterNames().isEmpty()) {
        compiledArguments.addToIr(ir);

        return compiler.compileConstantTo(ir, callExpression, constResult, result);
      }
    }
    return null;
  }

  /**
   * Try inline a function like:
   *
   * <pre>
   * def is_list(x): return type(x) == 'list'
   * </pre>
   */
  @Nullable
  private BcCompiler.CompileExpressionResult tryInlineTypeIsCall(
      BcIr ir,
      StarlarkCallable callable,
      CallCompiledArguments compiledArguments,
      BcIrLocalOrAny result) {
    StarlarkCallableLinkSig linkSig = compiledArguments.linkSig;
    CallExpression callExpression = compiledArguments.callExpression;

    if (callable instanceof StarlarkFunction && linkSig == StarlarkCallableLinkSig.positional(1)) {
      String type = ((StarlarkFunction) callable).returnsTypeIs();
      BcIrSlot argSlot = compiledArguments.callArgs.listArg.singleArg();
      Preconditions.checkState(argSlot != null);
      if (type != null) {
        compiledArguments.addToIr(ir);

        return compiler.writeTypeIs(ir, callExpression, argSlot, type, result);
      }
    }
    return null;
  }

  /** Try compile a call as a some form of linked call. */
  @Nullable
  private BcCompiler.CompileExpressionResult tryCompileCallLinked(
      BcIr ir, CallCompiledArguments compiledArguments, BcIrLocalOrAny result) {

    Object callableValue = compiledArguments.fn.result.value();
    if (!(callableValue instanceof StarlarkCallable)) {
      return null;
    }

    StarlarkCallable callable = (StarlarkCallable) callableValue;

    BcCompiler.CompileExpressionResult specSafeInlineResult =
        tryCompileSpecSafeInline(ir, callable, compiledArguments, result);
    if (specSafeInlineResult != null) {
      return specSafeInlineResult;
    }

    BcCompiler.CompileExpressionResult inlineConstResult =
        tryInlineConst(ir, callable, compiledArguments, result);
    if (inlineConstResult != null) {
      return inlineConstResult;
    }

    BcCompiler.CompileExpressionResult callCachedResult =
        tryCompileCallCached(ir, callable, compiledArguments, result);
    if (callCachedResult != null) {
      return callCachedResult;
    }

    BcCompiler.CompileExpressionResult inlineTypeIsResult =
        tryInlineTypeIsCall(ir, callable, compiledArguments, result);
    if (inlineTypeIsResult != null) {
      return inlineTypeIsResult;
    }

    StarlarkCallableLinked linked = callable.linkCall(compiledArguments.linkSig);

    compiledArguments.addToIr(ir);

    BcIrSlot.AnyLocal resultLocal = result.makeLocal("call");

    ir.add(
        new BcIrInstr.CallLinked(
            compiler.nodeToLocOffset(compiledArguments.callExpression),
            BcCallLocs.forExpression(compiler, compiledArguments.callExpression),
            linked,
            compiledArguments.callArgs.listArg,
            compiledArguments.star.slot,
            compiledArguments.starStar.slot,
            resultLocal));

    return new BcCompiler.CompileExpressionResult(resultLocal);
  }

  BcCompiler.CompileExpressionResult compileCall(
      BcIr ir, CallExpression callExpression, BcIrLocalOrAny result) {

    CallCompiledArguments compiledArguments = compileCallArguments(callExpression);

    BcCompiler.CompileExpressionResult callLinkedResult =
        tryCompileCallLinked(ir, compiledArguments, result);
    if (callLinkedResult != null) {
      return callLinkedResult;
    }

    compiledArguments.addToIr(ir);

    BcIrSlot.AnyLocal resultLocal = result.makeLocal("call");

    ir.add(
        new BcIrInstr.Call(
            compiler.nodeToLocOffset(callExpression),
            BcCallLocs.forExpression(compiler, callExpression),
            compiledArguments.fn.result.slot,
            compiledArguments.linkSig,
            compiledArguments.callArgs.listArg,
            compiledArguments.star.slot,
            compiledArguments.starStar.slot,
            resultLocal));

    return new BcCompiler.CompileExpressionResult(resultLocal);
  }
}
