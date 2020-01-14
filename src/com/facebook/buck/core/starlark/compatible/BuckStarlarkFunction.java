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

package com.facebook.buck.core.starlark.compatible;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.MethodDescriptor;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Marker class that makes some method exposable to skylark.
 *
 * <p>This class currently doesn't handle optionals and other java/skylark object coercing.
 */
public abstract class BuckStarlarkFunction
    implements com.google.devtools.build.lib.syntax.StarlarkFunction {

  private final MethodDescriptor methodDescriptor;
  private static MethodHandles.Lookup lookup = MethodHandles.lookup();
  private final MethodHandle method;

  /**
   * Creates a new skylark callable function of the given name that invokes the method handle. The
   * named parameters for skylark is the list of namedParams, which is mapped in order to the end of
   * the parameter list for the method handle.
   *
   * @param methodName the function name exposed to skylark
   * @param constructor the constructor that we will call as a method
   * @param namedParams a list of named parameters for skylark. The names are mapped in order to the
   *     parameters of {@code constructor}
   * @param defaultSkylarkValues a list of default values for parameters in skylark. The names are
   *     mapped in order to the parameters of {@code constructor}
   */
  public BuckStarlarkFunction(
      String methodName,
      Constructor<?> constructor,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    try {
      this.method = lookup.unreflectConstructor(constructor);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to access the supplied constructor", e);
    }
    this.methodDescriptor =
        inferMethodDescriptor(
            methodName, method, namedParams, defaultSkylarkValues, noneableParams);
  }

  /**
   * Creates a new skylark callable function of the given name that invokes the method handle. The
   * named parameters for skylark is the list of namedParams, which is mapped in order to the end of
   * the parameter list for the method handle.
   *
   * @param methodName the function name exposed to skylark
   * @param method a method that will eventually be called in {@link #call(List, Map,
   *     FuncallExpression, Environment)}
   * @param namedParams a list of named parameters for skylark. The names are mapped in order to the
   *     parameters of {@code method}
   * @param defaultSkylarkValues a list of default values for parameters in skylark. The values are
   *     mapped in order to the parameters of {@code method}
   */
  public BuckStarlarkFunction(
      String methodName,
      Method method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    try {
      this.method = lookup.unreflect(method);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to access the supplied method", e);
    }
    this.methodDescriptor =
        inferMethodDescriptor(
            methodName, this.method, namedParams, defaultSkylarkValues, noneableParams);
  }

  /**
   * Creates a new skylark callable function of the given name that invokes the method handle. The
   * named parameters for skylark is the list of namedParams, which is mapped in order to the end of
   * the parameter list for the method handle.
   *
   * @param methodName the function name exposed to skylark, which will be looked up via reflection
   * @param namedParams a list of named parameters for skylark. The names are mapped in order to the
   *     end of the parameters of the actual method.
   */
  @VisibleForTesting
  BuckStarlarkFunction(
      String methodName,
      ImmutableList<String> namedParams,
      ImmutableList<String> defaultSkylarkValues,
      Set<String> noneableParams)
      throws Throwable {
    this.method = lookup.unreflect(findMethod(methodName)).bindTo(this);
    this.methodDescriptor =
        inferMethodDescriptor(
            methodName, method, namedParams, defaultSkylarkValues, noneableParams);
  }

  /**
   * we infer a "fake" MethodDescriptor to be able to piggy back off some args and type processing
   * in skylark
   */
  private MethodDescriptor inferMethodDescriptor(
      String methodName,
      MethodHandle method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {

    try {
      return MethodDescriptor.of(
          BuckStarlarkFunction.class.getDeclaredMethod(
              "fake"), /* we hand a fake reflective method since we only use the MethodDescriptor to
                       piggy back off skylark's parameter handling. We don't actually have a
                       Method object to use in many cases (e.g. if the MethodHandle is a
                       constructor). */
          inferSkylarkCallableAnnotationFromMethod(
              methodName, method, namedParams, defaultSkylarkValues, noneableParams),
          BuckStarlark.BUCK_STARLARK_SEMANTICS);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException();
    }
  }

  @Override
  public Object call(
      List<Object> args,
      @Nullable Map<String, Object> kwargs,
      @Nullable FuncallExpression nullableAst,
      Environment env)
      throws EvalException, InterruptedException {

    FuncallExpression ast =
        Objects.requireNonNull(
            nullableAst, "AST should not be null as this should only be called from Starlark");

    // this is the effectively the same as bazel's {@BuiltInCallable}
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.STARLARK_BUILTIN_FN, methodDescriptor.getName())) {
      Object[] javaArguments =
          ast.convertStarlarkArgumentsToJavaMethodArguments(
              methodDescriptor, getClass(), args, kwargs, env);

      // TODO: deal with Optionals and some java/skylark object coercing
      ImmutableList<Object> argsForReflectionBuilder = ImmutableList.copyOf(javaArguments);

      // The below is adapted from bazel's MethodDescriptor.call, but for method handles
      try {
        Object result = method.invokeWithArguments(argsForReflectionBuilder);
        if (method.type().returnType().equals(Void.TYPE)) {
          return Runtime.NONE;
        }
        if (result == null) {
          if (methodDescriptor.isAllowReturnNones()) {
            return Runtime.NONE;
          } else {
            throw new EvalException(
                ast.getLocation(),
                "method invocation returned None, please file a bug report: "
                    + getName()
                    + Printer.printAbbreviatedList(
                        ImmutableList.copyOf(args), "(", ", ", ")", null));
          }
        }
        if (!EvalUtils.isSkylarkAcceptable(result.getClass())) {
          throw new EvalException(
              ast.getLocation(),
              Printer.format(
                  "method '%s' returns an object of invalid type %r",
                  getName(), result.getClass()));
        }
        return result;
      } catch (WrongMethodTypeException e) {
        throw new EvalException(ast.getLocation(), "Method invocation failed: " + e);
      } catch (Throwable e) {
        if (e.getCause() instanceof FuncallExpression.FuncallException) {
          throw new EvalException(ast.getLocation(), e.getCause().getMessage());
        } else if (e.getCause() != null) {
          Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
          throw new EvalException.EvalExceptionWithJavaCause(ast.getLocation(), e.getCause());
        } else {
          // This is unlikely to happen
          throw new EvalException(ast.getLocation(), "method invocation failed: " + e);
        }
      }
    }
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<built-in function " + methodDescriptor.getName() + ">");
  }

  private Method findMethod(String methodName) {
    @Nullable Method result = null;
    for (Method m : getClass().getDeclaredMethods()) {
      if (m.getName().equals(methodName) && Modifier.isPublic(m.getModifiers())) {
        if (result != null) {
          throw new IllegalArgumentException(
              String.format(
                  "%s contains more than one public %s(...) method", getClass(), methodName));
        }
        result = m;
      }
    }
    if (result == null) {
      throw new IllegalArgumentException(
          String.format("%s does not contain a public %s(...) method", getClass(), methodName));
    }
    result.setAccessible(true); // by pass security checks
    return result;
  }

  public String getName() {
    return getMethodDescriptor().getName();
  }

  @VisibleForTesting
  MethodDescriptor getMethodDescriptor() {
    return methodDescriptor;
  }

  private SkylarkCallable inferSkylarkCallableAnnotationFromMethod(
      String methodName,
      MethodHandle method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    return BuckStarlarkCallable.fromMethod(
        methodName, method, namedParams, defaultSkylarkValues, noneableParams);
  }

  // a fake method to hand to the MethodDescriptor that this uses.
  @SuppressWarnings("unused")
  private void fake() {}
}
