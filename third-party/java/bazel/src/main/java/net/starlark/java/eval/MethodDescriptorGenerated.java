package net.starlark.java.eval;

import com.google.errorprone.annotations.CheckReturnValue;

/**
 * This class is inherited in generated code
 * for faster reflective builtin function invocation.
 */
public abstract class MethodDescriptorGenerated {
  final String javaMethodName;

  public MethodDescriptorGenerated(String javaMethodName) {
    this.javaMethodName = javaMethodName;
  }

  public abstract Object invoke(Object receiver, Object[] args, StarlarkThread thread) throws Exception;

  @CheckReturnValue // don't forget to throw it
  protected NullPointerException methodInvocationReturnedNull(Object[] args) {
    return new NullPointerException(
        "method invocation returned null: " + javaMethodName + Tuple.of(args));
  }
}
