package com.facebook.buck.jvm.java.javax;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * ToolProvider has no synchronization internally, so if we don't synchronize from the
 * outside we could wind up loading the compiler classes multiple times from different
 * class loaders.
 */
public class SynchronizedToolProvider {

  public static JavaCompiler getSystemJavaCompiler() {
    JavaCompiler compiler;
    synchronized (ToolProvider.class) {
      compiler = ToolProvider.getSystemJavaCompiler();
    }
    return compiler;
  }

  public static ClassLoader getSystemToolClassLoader() {
    ClassLoader classLoader;
    synchronized (ToolProvider.class) {
      classLoader = ToolProvider.getSystemToolClassLoader();
    }
    return classLoader;
  }

}
