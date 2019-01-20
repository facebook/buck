package com.facebook.foo;

public class Dependency<T> {
  public class Inner {
    public class Innerer {}
  }

  public class NonGenericInner {
    public class GenericInnerer<U> {}
  }

  public class GenericInner<U> {}
}
