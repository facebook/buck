package com.facebook.buck.example;

public class StaticErrorTest {
  public static final int EXAMPLE = throwNpe();

  public static int throwNpe() {
    throw new NullPointerException();
  }
}
