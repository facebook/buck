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

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.spelling.SpellChecker;

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
  @Nullable private final MethodDescriptor desc;

  /**
   * Constructs a BuiltinFunction for a StarlarkMethod-annotated method of the given name (as seen
   * by Starlark, not Java).
   */
  BuiltinFunction(Object obj, String methodName) {
    this.obj = obj;
    this.methodName = methodName;
    this.desc = null; // computed later
  }

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
    public Object callLinked(StarlarkThread thread, Object[] args,
        @Nullable Sequence<?> starArgs, @Nullable Dict<?, ?> starStarArgs)
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
          StarlarkCallableLinkSig.of(positional.length, ArraysForStarlark.EMPTY_STRING_ARRAY, false, false),
          thread, positional, null, null);
    }

    String[] names = ArraysForStarlark.newStringArray(named.length >> 1);
    Object[] args = Arrays.copyOf(positional, positional.length + (names.length >> 1));

    for (int i = 0; i < names.length; i++) {
      names[i] = (String) named[i * 2]; // safe
      args[i + positional.length] = named[i * 2 + 1];
    }
    return linkAndCall(
        StarlarkCallableLinkSig.of(positional.length, names, false, false),
        thread, args, null, null);
  }

  @Override
  public StarlarkCallableLinked linkCall(StarlarkCallableLinkSig linkSig) {
    return new Linked(linkSig, this);
  }

  @Override
  public Object linkAndCall(StarlarkCallableLinkSig linkSig,
      StarlarkThread thread, Object[] args, @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs) throws InterruptedException, EvalException {
    MethodDescriptor desc = getMethodDescriptor(thread.getSemantics());

    long start = StarlarkRuntimeStats.ENABLED ? System.nanoTime() : 0;

    Object[] vector = getArgumentVector(thread, desc, linkSig, args, starArgs, starStarArgs);
    Object result = desc.call(
        obj instanceof String ? StringModule.INSTANCE : obj, vector, thread);

    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.recordNativeCall(getName(), System.nanoTime() - start);
    }

    return result;
  }

  private MethodDescriptor getMethodDescriptor(StarlarkSemantics semantics) {
    MethodDescriptor desc = this.desc;
    if (desc == null) {
      desc = CallUtils.getAnnotatedMethods(semantics, obj.getClass()).get(methodName);
      Preconditions.checkArgument(
          !desc.isStructField(),
          "BuiltinFunction constructed for MethodDescriptor(structField=True)");
    }
    return desc;
  }

  /**
   * Returns the StarlarkMethod annotation of this Starlark-callable Java method.
   */
  public StarlarkMethod getAnnotation() {
    return getMethodDescriptor(StarlarkSemantics.DEFAULT).getAnnotation();
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
   * @param desc descriptor for the StarlarkMethod-annotated method
   * @param linkSig
   * @return the array of arguments which may be passed to {@link MethodDescriptor#call}
   * @throws EvalException if the given set of arguments are invalid for the given method. For
   *     example, if any arguments are of unexpected type, or not all mandatory parameters are
   *     specified by the user
   */
  private Object[] getArgumentVector(
      StarlarkThread thread,
      MethodDescriptor desc, // intentionally shadows this.desc
      StarlarkCallableLinkSig linkSig, Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs)
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
    // Flag-disabled parameters are skipped during argument matching, as if they do not exist. They
    // are instead assigned their flag-disabled values.

    ParamDescriptor[] parameters = desc.getParameters();

    // fast track
    if (desc.isCanReusePositionalWithoutChecks() && linkSig.namedNames.length == 0 && args.length == parameters.length && starArgs == null && starStarArgs == null) {
      return args;
    }

    int numPositionals = linkSig.numPositionals + (starArgs != null ? starArgs.size() : 0);

    // Allocate argument vector.
    int n = parameters.length;
    if (desc.acceptsExtraArgs()) {
      n++;
    }
    if (desc.acceptsExtraKwargs()) {
      n++;
    }
    Object[] vector = new Object[n];

    // positional arguments
    int paramIndex = 0;
    int argIndex = 0;
    if (obj instanceof String) {
      // String methods get the string as an extra argument
      // because their true receiver is StringModule.INSTANCE.
      vector[paramIndex++] = obj;
    }
    for (; argIndex < numPositionals && paramIndex < parameters.length; paramIndex++) {
      ParamDescriptor param = parameters[paramIndex];
      if (!param.isPositional()) {
        break;
      }

      // disabled?
      if (param.disabledByFlag() != null) {
        // Skip disabled parameter as if not present at all.
        // The default value will be filled in below.
        continue;
      }

      Object value =
          argIndex < linkSig.numPositionals
              ? args[argIndex++]
              : starArgs.get(argIndex++ - linkSig.numPositionals);
      checkParamValue(param, value);
      vector[paramIndex] = value;
    }

    // *args
    Tuple varargs = null;
    if (desc.acceptsExtraArgs()) {
      Object[] varargsArray = ArraysForStarlark.newObjectArray(numPositionals - argIndex);
      int i = 0;
      while (argIndex < numPositionals) {
        varargsArray[i++] = argIndex < linkSig.numPositionals
            ? args[argIndex] : starArgs.get(argIndex - linkSig.numPositionals);
        ++argIndex;
      }
      Preconditions.checkState(i == varargsArray.length);
      varargs = Tuple.wrap(varargsArray);
    } else if (argIndex < numPositionals) {
      if (argIndex == 0) {
        throw Starlark.errorf("%s() got unexpected positional argument", methodName);
      } else {
        throw Starlark.errorf(
            "%s() accepts no more than %d positional argument%s but got %d",
            methodName, argIndex, plural(argIndex), numPositionals);
      }
    }

    // named arguments
    LinkedHashMap<String, Object> kwargs = desc.acceptsExtraKwargs() ? new LinkedHashMap<>() : null;
    for (int i = 0; i < linkSig.namedNames.length; ++i) {
      String name = linkSig.namedNames[i];
      Object value = args[linkSig.numPositionals + i];
      handleNamedArg(thread, desc, vector, kwargs, name, value);
    }
    if (starStarArgs != null) {
      for (Map.Entry<?, ?> entry : starStarArgs.contents.entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          throw new EvalException("TODO: better message");
        }
        String name = (String) entry.getKey();
        Object value = entry.getValue();
        handleNamedArg(thread, desc, vector, kwargs, name, value);
      }
    }

    // Set default values for missing parameters,
    // and report any that are still missing.
    MissingParams missing = null;
    for (int i = 0; i < parameters.length; i++) {
      if (vector[i] == null) {
        ParamDescriptor param = parameters[i];
        vector[i] = param.getDefaultValue();
        if (vector[i] == null) {
          if (missing == null) {
            missing = new MissingParams(getName());
          }
          if (param.isPositional()) {
            missing.addPositional(param.getName());
          } else {
            missing.addNamed(param.getName());
          }
        }
      }
    }

    if (missing != null) {
      throw missing.error();
    }

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

  private void handleNamedArg(StarlarkThread thread, MethodDescriptor desc,
      final Object[] vector, final LinkedHashMap<String, Object> kwargs, final String name, final Object value)
      throws EvalException {
    ParamDescriptor[] parameters = desc.getParameters();;

    // look up parameter
    int index = desc.getParameterIndex(name);
    // unknown parameter?
    if (index < 0) {
      // spill to **kwargs
      if (kwargs == null) {
        List<String> allNames =
            Arrays.stream(parameters)
                .map(ParamDescriptor::getName)
                .collect(ImmutableList.toImmutableList());
        throw Starlark.errorf(
            "%s() got unexpected keyword argument '%s'%s",
            methodName, name, SpellChecker.didYouMean(name, allNames));
      }

      // duplicate named argument?
      if (kwargs.put(name, value) != null) {
        throw Starlark.errorf(
            "%s() got multiple values for keyword argument '%s'", methodName, name);
      }
      return;
    }
    ParamDescriptor param = parameters[index];

    // positional-only param?
    if (!param.isNamed()) {
      // spill to **kwargs
      if (kwargs == null) {
        throw Starlark.errorf(
            "%s() got named argument for positional-only parameter '%s'", methodName, name);
      }

      // duplicate named argument?
      if (kwargs.put(name, value) != null) {
        throw Starlark.errorf(
            "%s() got multiple values for keyword argument '%s'", methodName, name);
      }
      return;
    }

    // disabled?
    String flag = param.disabledByFlag();
    if (flag != null) {
      // spill to **kwargs
      if (kwargs == null) {
        throw Starlark.errorf(
            "in call to %s(), parameter '%s' is %s",
            methodName, param.getName(), disabled(flag, thread.getSemantics()));
      }

      // duplicate named argument?
      if (kwargs.put(name, value) != null) {
        throw Starlark.errorf(
            "%s() got multiple values for keyword argument '%s'", methodName, name);
      }
      return;
    }

    checkParamValue(param, value);

    // duplicate?
    if (vector[index] != null) {
      throw Starlark.errorf("%s() got multiple values for argument '%s'", methodName, name);
    }

    vector[index] = value;
  }

  private static String plural(int n) {
    return n == 1 ? "" : "s";
  }

  private void checkParamValue(ParamDescriptor param, Object value) throws EvalException {
    if (param.isAllowedClassesContainObject()) {
      return;
    }

    // Value must belong to one of the specified classes.
    boolean ok = false;
    for (Class<?> cls : param.getAllowedClasses()) {
      if (cls.isInstance(value)) {
        ok = true;
        break;
      }
    }
    if (!ok) {
      throw Starlark.errorf(
          "in call to %s(), parameter '%s' got value of type '%s', want '%s'",
          methodName, param.getName(), Starlark.type(value), param.getTypeErrorMessage());
    }
  }

  // Returns a phrase meaning "disabled" appropriate to the specified flag.
  private static String disabled(String flag, StarlarkSemantics semantics) {
    // If the flag is True, it must be a deprecation flag. Otherwise it's an experimental flag.
    // TODO(adonovan): is that assumption sound?
    if (semantics.getBool(flag)) {
      return String.format(
          "deprecated and will be removed soon. It may be temporarily re-enabled by setting"
              + " --%s=false",
          flag.substring(1)); // remove [+-] prefix
    } else {
      return String.format(
          "experimental and thus unavailable with the current flags. It may be enabled by setting"
              + " --%s",
          flag.substring(1)); // remove [+-] prefix
    }
  }

  public boolean isSpeculativeSafe() {
    return desc != null && desc.isSpeculativeSafe();
  }
}
