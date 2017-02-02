package com.example;

import org.junit.Test;

public class BadStaticInitializerTest {
  @Test
  public void testThatRequiresClassloading() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadStaticInitializerImpl");
  }

  @Test
  public void testThatFindsProvidedInstanceMethodFromSubclass() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadStaticInitializerImpl");
    clazz.getMethod("provided");
  }

  @Test
  public void testThatFindsRuntimeInstanceMethodFromSubclass() throws Exception {
    Class<?> clazz = Class.forName("com.example.BadStaticInitializerImpl");
    clazz.getMethod("runtime");
  }

  @Test
  public void testThatAccessesStaticFieldThatCantBeInitialized() {
    String value = BadStaticInitializerImpl.field;
  }
}
