package com.example;

/**
 * This class invokes a method from its subclass A in an instance method.
 */
public class BadInstanceMethodImpl extends A {
  public String method() {
    return provided();
  }
}

