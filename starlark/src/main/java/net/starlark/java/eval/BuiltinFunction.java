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

// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.starlark.java.annot.FnPurity;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;

/**
 * A BuiltinFunction is a callable Starlark value that reflectively invokes a {@link
 * StarlarkMethod}-annotated method of a Java object. The Java object may or may not itself be a
 * Starlark value. BuiltinFunctions are not produced for Java methods for which {@link
 * StarlarkMethod#structField} is true.
 */
// TODO(adonovan): support annotated static methods.
@StarlarkBuiltin(
    name = "builtin_function_or_method", // (following Python)
    category = "core",
    doc = "The type of a built-in function, defined by Java code.")
public final class BuiltinFunction extends StarlarkCallable {

  private final Object obj;
  private final String methodName;
  final MethodDescriptor desc;

  /**
   * Constructs a BuiltinFunction for a StarlarkMethod-annotated method (not field) of the given
   * name (as seen by Starlark, not Java).
   *
   * <p>This constructor should be used only for ephemeral BuiltinFunction values created
   * transiently during a call such as {@code x.f()}, when the caller has already looked up the
   * MethodDescriptor using the same semantics as the thread that will be used in the call. Use the
   * other (slower) constructor if there is any possibility that the semantics of the {@code x.f}
   * operation differ from those of the thread used in the call.
   */
  BuiltinFunction(Object obj, String methodName, MethodDescriptor desc) {
    Preconditions.checkArgument(!desc.isStructField());
    this.obj = obj;
    this.methodName = methodName;
    this.desc = desc;
  }

  private static class Linked extends StarlarkCallableLinked {
    protected Linked(StarlarkCallableLinkSig linkSig, BuiltinFunction builtinFunction) {
      super(linkSig, builtinFunction);
    }

    @Override
    public Object callLinked(
        StarlarkThread thread,
        Object[] args,
        @Nullable Sequence<?> starArgs,
        @Nullable Dict<?, ?> starStarArgs)
        throws EvalException, InterruptedException {
      BuiltinFunction builtinFunction = (BuiltinFunction) orig;
      return builtinFunction.linkAndCall(linkSig, thread, args, starArgs, starStarArgs);
    }
  }

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    // fast track
    if (named.length == 0) {
      return linkAndCall(
          StarlarkCallableLinkSig.of(
              positional.length, ArraysForStarlark.EMPTY_STRING_ARRAY, false, false),
          thread,
          positional,
          null,
          null);
    }

    String[] names = ArraysForStarlark.newStringArray(named.length >> 1);
    Object[] args = Arrays.copyOf(positional, positional.length + (names.length >> 1));

    for (int i = 0; i < names.length; i++) {
      names[i] = (String) named[i * 2]; // safe
      args[i + positional.length] = named[i * 2 + 1];
    }
    return linkAndCall(
        StarlarkCallableLinkSig.of(positional.length, names, false, false),
        thread,
        args,
        null,
        null);
  }

  @Override
  public StarlarkCallableLinked linkCall(StarlarkCallableLinkSig linkSig) {
    return new Linked(linkSig, this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object linkAndCall(
      StarlarkCallableLinkSig linkSig,
      StarlarkThread thread,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs)
      throws InterruptedException, EvalException {
    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.enter(StarlarkRuntimeStats.WhereWeAre.NATIVE_CALL);
    }

    try {
      try {
        if (linkSig.isPosOnly()) {
          return desc.callPos(obj, args, thread);
        } else {
          Object[] vector =
              getArgumentVector(
                  thread, linkSig, args, starArgs, (Dict<Object, Object>) starStarArgs);

          return desc.call(obj, vector, thread);
        }
      } catch (MethodDescriptorGenerated.ArgumentBindException e) {
        throw BuiltinFunctionLinkedError.error(
            this, linkSig, args, starArgs, (Dict<Object, Object>) starStarArgs);
      }
    } finally {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.leaveNativeCall(getName());
      }
    }
  }

  /** Returns the StarlarkMethod annotation of this Starlark-callable Java method. */
  public StarlarkMethod getAnnotation() {
    return this.desc.getAnnotation();
  }

  @Override
  public String getName() {
    return methodName;
  }

  @Override
  public void repr(Printer printer) {
    if (obj instanceof StarlarkValue || obj instanceof String) {
      printer
          .append("<built-in method ")
          .append(methodName)
          .append(" of ")
          .append(Starlark.type(obj))
          .append(" value>");
    } else {
      printer.append("<built-in function ").append(methodName).append(">");
    }
  }

  @Override
  public String toString() {
    return methodName;
  }

  /**
   * Converts the arguments of a Starlark call into the argument vector for a reflective call to a
   * StarlarkMethod-annotated Java method.
   *
   * @param thread the Starlark thread for the call
   * @param linkSig
   * @return the array of arguments which may be passed to {@link MethodDescriptor#call}
   * @throws EvalException if the given set of arguments are invalid for the given method. For
   *     example, if any arguments are of unexpected type, or not all mandatory parameters are
   *     specified by the user
   */
  private Object[] getArgumentVector(
      StarlarkThread thread,
      StarlarkCallableLinkSig linkSig,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<Object, Object> starStarArgs)
      throws EvalException {

    // Overview of steps:
    // - allocate vector of actual arguments of correct size.
    // - process positional arguments, accumulating surplus ones into *args.
    // - process named arguments, accumulating surplus ones into **kwargs.
    // - set default values for missing optionals, and report missing mandatory parameters.
    // - set special parameters.
    // The static checks ensure that positional parameters appear before named,
    // and mandatory positionals appear before optional.
    // No additional memory allocation occurs in the common (success) case.

    ParamDescriptor[] parameters = desc.getParameters();

    int numPositionals = linkSig.numPositionals + (starArgs != null ? starArgs.size() : 0);

    // Allocate argument vector.
    Object[] vector = new Object[desc.getArgsSize()];

    // positional arguments
    int paramIndex = 0;
    int argIndex = 0;
    for (; argIndex < numPositionals && paramIndex < parameters.length; paramIndex++) {
      ParamDescriptor param = parameters[paramIndex];
      if (!param.isPositional()) {
        break;
      }

      Object value =
          argIndex < linkSig.numPositionals
              ? args[argIndex++]
              : starArgs.get(argIndex++ - linkSig.numPositionals);
      vector[paramIndex] = value;
    }

    // *args
    Tuple varargs = null;
    if (desc.acceptsExtraArgs()) {
      Object[] varargsArray = ArraysForStarlark.newObjectArray(numPositionals - argIndex);
      int i = 0;
      while (argIndex < numPositionals) {
        varargsArray[i++] =
            argIndex < linkSig.numPositionals
                ? args[argIndex]
                : starArgs.get(argIndex - linkSig.numPositionals);
        ++argIndex;
      }
      Preconditions.checkState(i == varargsArray.length);
      varargs = Tuple.wrap(varargsArray);
    } else if (argIndex < numPositionals) {
      throw BuiltinFunctionLinkedError.error(this, linkSig, args, starArgs, starStarArgs);
    }

    // named arguments

    DictMap<String, Object> kwargs;
    if (desc.acceptsExtraKwargs()) {
      // Only check for collision if there's **kwargs param,
      // otherwise duplicates will be handled when populating named parameters.
      if (StarlarkCallableUtils.hasKeywordCollision(linkSig, starStarArgs) != null) {
        throw BuiltinFunctionLinkedError.error(this, linkSig, args, starArgs, starStarArgs);
      }

      int kwargsCap = linkSig.namedNames.length + (starStarArgs != null ? starStarArgs.size() : 0);
      kwargs = new DictMap<>(kwargsCap);
    } else {
      kwargs = null;
    }
    for (int i = 0; i < linkSig.namedNames.length; ++i) {
      String key = linkSig.namedNames[i];
      int keyHash = linkSig.namedNameDictHashes[i];
      Object value = args[linkSig.numPositionals + i];
      handleNamedArg(vector, linkSig, args, starArgs, starStarArgs, kwargs, key, keyHash, value);
    }
    if (starStarArgs != null) {
      DictMap.Node<?, ?> node = starStarArgs.contents.getFirst();
      while (node != null) {
        if (!(node.key instanceof String)) {
          throw BuiltinFunctionLinkedError.error(this, linkSig, args, starArgs, starStarArgs);
        }
        String key = (String) node.key;
        int keyHash = node.keyHash;
        Object value = node.getValue();
        handleNamedArg(vector, linkSig, args, starArgs, starStarArgs, kwargs, key, keyHash, value);
        node = node.getNext();
      }
    }

    // Default values and missing parameters are populated in generated descriptors

    // special parameters
    int i = parameters.length;
    if (desc.acceptsExtraArgs()) {
      vector[i++] = varargs;
    }
    if (desc.acceptsExtraKwargs()) {
      vector[i++] = Dict.wrap(thread.mutability(), kwargs);
    }

    return vector;
  }

  private void handleNamedArg(
      Object[] vector,
      StarlarkCallableLinkSig linkSig,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<Object, Object> starStarArgs,
      @Nullable DictMap<String, Object> kwargs,
      String key,
      int keyHash,
      Object value)
      throws EvalException {
    ParamDescriptor[] parameters = desc.getParameters();

    // look up parameter
    int index = desc.getParameterIndex(key);
    // unknown parameter?
    // positional-only param?
    if (index < 0 || !parameters[index].isNamed()) {
      // spill to **kwargs
      if (kwargs == null) {
        throw BuiltinFunctionLinkedError.error(this, linkSig, args, starArgs, starStarArgs);
      }

      kwargs.putNoEvictNoResize(key, keyHash, value);
      return;
    }

    // duplicate?
    if (vector[index] != null) {
      throw BuiltinFunctionLinkedError.error(this, linkSig, args, starArgs, starStarArgs);
    }

    vector[index] = value;
  }

  public FnPurity purity() {
    if (desc != null) {
      return desc.getPurity();
    } else {
      return FnPurity.DEFAULT;
    }
  }
}
