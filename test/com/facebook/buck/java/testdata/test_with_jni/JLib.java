package jlib;

public class JLib {
  static {
    System.loadLibrary("jlib-native");
  }

  public static int getValue() {
    return 1 + nativeGetPreValue();
  }
  private static native int nativeGetPreValue();
}
