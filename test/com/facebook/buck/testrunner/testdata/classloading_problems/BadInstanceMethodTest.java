package com.example;

import org.junit.Test;

public class BadInstanceMethodTest {
  @Test
  public void testThatRequiresClassloading() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadInstanceMethodImpl");
  }

  @Test
  public void testThatFindsMethodFromClass() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadInstanceMethodImpl");
    clazz.getMethod("method");
  }

  @Test
  public void testThatFindsProvidedInstanceMethodFromSubclass() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadInstanceMethodImpl");
    clazz.getMethod("provided");
  }

  @Test
  public void testThatFindsRuntimeInstanceMethodFromSubclass() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadInstanceMethodImpl");
    clazz.getMethod("runtime");
  }

  @Test
  public void testThatInvokesBadInstanceMethod() {
    String value = new BadInstanceMethodImpl().method();
  }
}
