package net.starlark.java.eval;

/**
 * This class is inherited in generated code
 * for faster reflective builtin function invocation.
 */
public abstract class MethodDescriptorGenerated {
  final String javaMethodName;

  public MethodDescriptorGenerated(String javaMethodName) {
    this.javaMethodName = javaMethodName;
  }

  public abstract Object invoke(Object receiver, Object[] args) throws Exception;
}
