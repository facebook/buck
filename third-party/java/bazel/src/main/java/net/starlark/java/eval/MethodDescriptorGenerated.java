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

import com.google.errorprone.annotations.CheckReturnValue;
import java.util.Arrays;

/** This class is inherited in generated code for faster reflective builtin function invocation. */
public abstract class MethodDescriptorGenerated {
  final String javaMethodName;
  private final String fnName;

  public MethodDescriptorGenerated(String javaMethodName, String fnName) {
    this.javaMethodName = javaMethodName;
    this.fnName = fnName;
  }

  /**
   * Invoke the generated descriptor.
   *
   * @param args is the array of arguments matching function arguments. This array may contain nulls
   *     for arguments to be filled with default values.
   */
  public abstract Object invoke(Object receiver, Object[] args, StarlarkThread thread)
      throws Exception;

  /**
   * Invoke the generated descriptor with positional arguments.
   *
   * @param args is the array of positional arguments. This array may be longer or shorter than
   *     function parameters, it's generated code responsibility to handle it.
   */
  public abstract Object invokePos(Object receiver, Object[] args, StarlarkThread thread)
      throws Exception;

  @CheckReturnValue // don't forget to throw it
  protected NullPointerException methodInvocationReturnedNull(Object[] args) {
    return new NullPointerException(
        "method invocation returned null: " + javaMethodName + Tuple.of(args));
  }

  @CheckReturnValue
  protected EvalException notAllowedArgument(
      String paramName, Object value, Class<?>[] allowedClasses) {
    return Starlark.errorf(
        "in call to %s(), parameter '%s' got value of type '%s', want '%s'",
        fnName,
        paramName,
        Starlark.type(value),
        ParamDescriptor.typeErrorMessage(Arrays.asList(allowedClasses)));
  }

  /** Evaluate parameter default value. */
  protected static Object evalDefault(String name, String expr) {
    // `ParamDescriptor.evalDefault` is package private, so this function
    // exists here to provide access to it from generated code.
    return ParamDescriptor.evalDefault(name, expr);
  }

  /** Copy/wrap remaining args into tuple. This is called from generated code. */
  protected static Tuple tupleFromRemArgs(Object[] args, int offset) {
    if (offset == 0) {
      // Safe: caller won't modify the array
      return Tuple.wrap(args);
    } else if (offset >= args.length) {
      // Offset can exceed args length when some arguments have defaults, for example,
      // for a native function `def foo(x=1, *args)` and call `foo()`,
      // `args=[]` and `offset=1`.
      return Tuple.empty();
    } else {
      return Tuple.wrap(Arrays.copyOfRange(args, offset, args.length));
    }
  }

  /**
   * Call {@link StarlarkThread#recordSideEffect()}, but make this function accessible to generated
   * code ({@link StarlarkThread#recordSideEffect()} is package-local).
   */
  protected static void recordSideEffect(StarlarkThread thread) {
    thread.recordSideEffect();
  }

  /**
   * This exception is thrown by generated descriptor trampoline when some arguments are incorrect.
   * There's no details in this exception, because when it is caught, slow error handler will
   * produce detailed error message.
   *
   * <p>Error message may be produced by the generated descriptor, but that would compliate codegen,
   * and probably slow down fast execution path.
   */
  public static class ArgumentBindException extends Exception {}
}
