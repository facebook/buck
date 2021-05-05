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
package net.starlark.java.eval;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.Resolver;

/** A StarlarkFunction is a function value created by a Starlark {@code def} statement. */
@StarlarkBuiltin(
    name = "function",
    category = "core",
    doc = "The type of functions declared in Starlark.")
public final class StarlarkFunction extends StarlarkCallable {

  private final String name;
  private final boolean isTopLevel;
  private final ImmutableList<String> parameterNames;
  final int numNonStarParams;
  final int numKeywordOnlyParams;
  final int varargsIndex;
  final int kwargsIndex;
  final int[] cellIndices;
  private final Location location;

  private final Module module; // a function closes over its defining module

  // Default values of optional parameters.
  // Indices correspond to the subsequence of parameters after the initial
  // required parameters and before *args/**kwargs.
  // Contain MANDATORY for the required keyword-only parameters.
  final Tuple defaultValues;

  // Cells (shared locals) of enclosing functions.
  // Indexed by Resolver.Binding(FREE).index values.
  private final Tuple freevars;

  final BcCompiled compiled;

  StarlarkFunction(
      StarlarkThread thread,
      Resolver.Function rfn,
      Module module,
      Tuple defaultValues,
      Tuple freevars) {

    // Here we copy `rfn` fields to this fields
    // to release memory allocated for AST.
    this.name = rfn.getName();
    this.isTopLevel = rfn.isToplevel();
    this.parameterNames = rfn.getParameterNames();
    this.numNonStarParams = rfn.numNonStarParams();
    this.numKeywordOnlyParams = rfn.numKeywordOnlyParams();
    this.varargsIndex = rfn.getVarargsIndex();
    this.kwargsIndex = rfn.getKwargsIndex();
    this.cellIndices = rfn.getCellIndices();
    this.location = rfn.getLocation();

    this.module = module;
    this.defaultValues = defaultValues;
    this.freevars = freevars;

    this.compiled = Bc.compileFunction(thread, rfn, module, freevars);
  }

  boolean isToplevel() {
    return isTopLevel;
  }

  // TODO(adonovan): many functions would be simpler if
  // parameterNames excluded the *args and **kwargs parameters,
  // (whose names are immaterial to the callee anyway). Do that.
  // Also, reject getDefaultValue for varargs and kwargs.

  /**
   * Returns the default value of the ith parameter ({@code 0 <= i < getParameterNames().size()}),
   * or null if the parameter is required. Residual parameters, if any, are always last, and have no
   * default value.
   */
  @Nullable
  public Object getDefaultValue(int i) {
    int nparams = numNonStarParams;
    int prefix = nparams - defaultValues.size();
    if (i < prefix) {
      return null; // implicit prefix of mandatory parameters
    }
    if (i < nparams) {
      Object v = defaultValues.get(i - prefix);
      return v == MANDATORY ? null : v;
    }
    return null; // *args or *kwargs
  }

  /**
   * Returns the names of this function's parameters. The residual {@code *args} and {@code
   * **kwargs} parameters, if any, are always last.
   */
  public ImmutableList<String> getParameterNames() {
    return parameterNames;
  }

  /**
   * Reports whether this function has a residual positional arguments parameter, {@code def
   * f(*args)}.
   */
  public boolean hasVarargs() {
    return varargsIndex >= 0;
  }

  /**
   * Reports whether this function has a residual keyword arguments parameter, {@code def
   * f(**kwargs)}.
   */
  public boolean hasKwargs() {
    return kwargsIndex >= 0;
  }

  /** Returns the location of the function's defining identifier. */
  @Override
  public Location getLocation() {
    return location;
  }

  @Override
  public Object linkAndCall(StarlarkCallableLinkSig linkSig,
      StarlarkThread thread, Object[] args, @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs) throws InterruptedException, EvalException {
    return linkCall(linkSig).callLinked(thread, args, starArgs, starStarArgs);
  }

  private StarlarkCallableLinked linkCache;

  @Override
  public StarlarkCallableLinked linkCall(StarlarkCallableLinkSig sig) {
    StarlarkCallableLinked nanoCache = this.linkCache;
    if (nanoCache != null && nanoCache.linkSig == sig) {
      return nanoCache;
    }
    return this.linkCache = linkCallImpl(sig);
  }

  private StarlarkCallableLinked linkCallImpl(StarlarkCallableLinkSig sig) {
    // nparams is the number of ordinary parameters.
    int nparams = numNonStarParams;

    // numPositionalParams is the number of non-kwonly parameters.
    int numPositionalParams = nparams - numKeywordOnlyParams;

    if (sig.namedNames.length == 0 && !sig.hasStar && !sig.hasStarStar) {
      if (!hasVarargs() && !hasKwargs() && numKeywordOnlyParams == 0 && nparams == sig.numPositionals) {
        // positional-only invocation
        return new StarlarkFunctionLinkedPos(sig, this);
      }
    }

    int[] paramFromArg = new int[nparams];
    Arrays.fill(paramFromArg, Integer.MIN_VALUE);
    IntArrayBuilder argToStar = new IntArrayBuilder();
    IntArrayBuilder argToStarStar = new IntArrayBuilder();
    ArrayList<String> argToStarStarName = new ArrayList<>();

    List<String> unexpected = null;

    for (int argIndex = 0; argIndex < sig.numPositionals; ++argIndex) {
      if (argIndex < numPositionalParams) {
        paramFromArg[argIndex] = argIndex;
      } else if (hasVarargs()) {
        argToStar.add(argIndex);
      } else {
        return new StarlarkFunctionLinkedErrorTooManyPositionals(this, sig);
      }
    }

    for (int i = 0, namedLength = sig.namedNames.length; i < namedLength; i++) {
      int argIndex = sig.numPositionals + i;
      String argName = sig.namedNames[i];
      int paramIndex = parameterNames.indexOf(argName);
      if (paramIndex >= 0 && paramIndex < nparams) {
        // duplicate named param
        if (paramFromArg[paramIndex] == Integer.MIN_VALUE) {
          paramFromArg[paramIndex] = argIndex;
        } else {
          return new StarlarkCallableLinkedError(this, sig, String.format(
              "%s() got multiple values for parameter '%s'",
              getName(),
              argName
          ));
        }
      } else if (hasKwargs()) {
        argToStarStar.add(argIndex);
        argToStarStarName.add(argName);
      } else {
        if (unexpected == null) {
          unexpected = new ArrayList<>();
        }
        unexpected.add(argName);
      }
    }

    if (unexpected != null) {
      // Give a spelling hint if there is exactly one.
      // More than that suggests the wrong function was called.
      return new StarlarkCallableLinkedError(this, sig, String.format(
          "%s() got unexpected keyword argument%s: %s%s",
          getName(),
          plural(unexpected.size()),
          Joiner.on(", ").join(unexpected),
          unexpected.size() == 1
              ? SpellChecker.didYouMean(unexpected.get(0), parameterNames.subList(0, nparams))
              : ""));
    }

    return new StarlarkFunctionLinked(
        this,
        paramFromArg,
        argToStar.buildArray(),
        argToStarStar.buildArray(),
        argToStarStarName.toArray(ArraysForStarlark.EMPTY_STRING_ARRAY),
        sig);
  }

  /**
   * Returns the name of the function, or "lambda" if anonymous. Implicit functions (those not
   * created by a def statement), may have names such as "<toplevel>" or "<expr>".
   */
  @Override
  public String getName() {
    return name;
  }

  public Module getModule() {
    return module;
  }

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {

    // This is mostly dead code, fastcall is not invoked by the interpterer,
    // only by users? or by `sorted` builtin.
    // Still do the fast track optimization to be on the safe size.
    if (named.length == 0) {
      StarlarkCallableLinkSig linkSig = StarlarkCallableLinkSig.of(
          positional.length, ArraysForStarlark.EMPTY_STRING_ARRAY, false, false);

      return linkCall(linkSig).callLinked(thread, positional, null, null);
    } else {
      String[] names = new String[named.length / 2];
      Object[] allArgs = Arrays.copyOf(positional, positional.length + named.length / 2);
      for (int i = 0; i != names.length; ++i) {
        names[i] = (String) named[i * 2];
        allArgs[positional.length + i] = named[i * 2 + 1];
      }
      StarlarkCallableLinkSig linkSig = StarlarkCallableLinkSig.of(
          positional.length, names, false, false);
      return linkCall(linkSig).callLinked(thread, allArgs, null, null);
    }
  }

  Cell getFreeVar(int index) {
    return (Cell) freevars.get(index);
  }

  @Override
  public void repr(Printer printer) {
    // TODO(adonovan): use the file name instead. But that's a breaking Bazel change.
    Object clientData = module.getClientData();

    printer.append("<function " + getName());
    if (clientData != null) {
      printer.append(" from " + clientData);
    }
    printer.append(">");
  }

  static String plural(int n) {
    return n == 1 ? "" : "s";
  }

  @Override
  public String toString() {
    StringBuilder out = new StringBuilder();
    out.append(getName());
    out.append('(');
    String sep = "";
    // TODO(adonovan): include *, ** tokens.
    for (String param : getParameterNames()) {
      out.append(sep).append(param);
      sep = ", ";
    }
    out.append(')');
    return out.toString();
  }

  @Override
  public boolean isImmutable() {
    // Only correct because closures are not yet supported.
    return true;
  }

  // The MANDATORY sentinel indicates a slot in the defaultValues
  // tuple corresponding to a required parameter.
  // It is not visible to Java or Starlark code.
  static final Object MANDATORY = new Mandatory();

  private static class Mandatory extends StarlarkValue {}

  // A Cell is a local variable shared between an inner and an outer function.
  // It is a StarlarkValue because it is a stack operand and a Tuple element,
  // but it is not visible to Java or Starlark code.
  static final class Cell extends StarlarkValue {
    Object x;

    Cell(Object x) {
      this.x = x;
    }
  }

  @Nullable
  Object returnsConst() {
    return compiled.returnConst();
  }
}
