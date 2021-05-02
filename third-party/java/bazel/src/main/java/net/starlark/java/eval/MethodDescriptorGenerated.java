package net.starlark.java.eval;

import com.google.errorprone.annotations.CheckReturnValue;
import java.util.Arrays;

/**
 * This class is inherited in generated code
 * for faster reflective builtin function invocation.
 */
public abstract class MethodDescriptorGenerated {
  final String javaMethodName;
  private final String fnName;

  public MethodDescriptorGenerated(String javaMethodName, String fnName) {
    this.javaMethodName = javaMethodName;
    this.fnName = fnName;
  }

  public abstract Object invoke(Object receiver, Object[] args, StarlarkThread thread) throws Exception;

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
}
