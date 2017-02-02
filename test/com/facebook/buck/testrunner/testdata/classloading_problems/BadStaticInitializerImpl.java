package com.example;

/**
 * This class invokes a method from its subclass during static initialization.
 */
public class BadStaticInitializerImpl extends A {
  public static String field = A.provided();
}

