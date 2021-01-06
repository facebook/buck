package com.facebook.buck.jvm.java.abi.source;

import java.util.concurrent.Callable;

public abstract class Test {
  protected void foo() {

  /*}*/

  // Omitting the close bracket above makes javac parse this as if it were a local, but "abstract"
  // cannot appear on local variables, so it creates an ErroneousTree. Running analyze with an
  // ErroneousTree present crashes the compiler.
  public abstract void bar();
}
