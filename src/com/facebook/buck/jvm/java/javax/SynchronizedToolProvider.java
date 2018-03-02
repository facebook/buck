package com.facebook.buck.jvm.java.javax;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

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
