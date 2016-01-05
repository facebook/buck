package com.example.modern;

public class SwitchOnStrings {
  public static String result(final String input) {
    switch (input) {
      case "foo":
        return "OK";
      default:
        return "Oh dear";
    }
  }
}
