package com.example.buck;

public class Main {
  // Reference to a constant in the same package within an if condition in
  // a static initializer or anonymous block should not result in an error
  // during source ABI generation.
  static {
    if (Lib.VALUE) {}
  }

  {
    if (Lib.VALUE) {}
  }

  public static void main(String[] args) {
    Lib l = new Lib();
    Test t = new Test();
  }
}
