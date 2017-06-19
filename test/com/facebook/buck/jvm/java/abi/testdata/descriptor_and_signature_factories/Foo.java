package com.facebook.foo;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Contains a bunch of different types referenced in different places, for testing all the corners
 * of descriptor and signature formatting.
 */
public abstract class Foo {
  // Primitives
  abstract void voidReturn();
  boolean booleanField;
  byte byteField;
  char charField;
  short shortField;
  int intField;
  long longField;
  float floatField;
  double doubleField;
  int[] primitiveArrayField;

  // Types
  String topLevelClassField;
  Map.Entry nestedClassField;
  Object[] objectArrayField;
  List<String> genericField;
  List<?> wildcardField;
  List<? super String> superWildcardField;
  List<? extends Runnable> extendsWildcardField;
  List<Set<String>> nestedGenericField;
  List<String>[] genericArrayField;
  Object[][] multiDimObjectArrayField;

  // Methods
  abstract String parameterlessMethod();
  abstract int methodWithParameters(int p1, int p2);
  abstract void methodWithGenericParams(List<String> a, int b);
  abstract <X, Y, Z> Z methodWithTypeVarParameters(X x, List<Y> y, Set<? extends Z> z);
  abstract String parameterlessThrowingMethod() throws Exception;
  abstract <T extends Exception> void typevarThrowingMethod() throws T, Exception;
  abstract <T extends Exception> void nonGenericThrowingGenericMethod() throws Exception;
  abstract List<String> genericReturningMethod();

  // Type vars
  abstract <T extends Runnable & CharSequence> void typeVarWithMultipleBounds(T t);
  abstract <T extends CharSequence & Runnable> void typeVarWithMultipleBoundsInDifferentOrder(T t);

  // Classes
  class NonGenericClass {}
  class SimpleGenericClass<T> {}
  interface SimpleGenericInterface<T> {}
  abstract class ClassWithTypeVariableParameterizedSuper<T> extends ArrayList<T> {}
  abstract class ClassWithParameterizedSuper extends ArrayList<Integer> {}
  abstract class ClassWithParameterizedInterfaces implements List<Integer>, Comparable<String> {}
  class ClassWithInterfaceBoundedTypeParameter<T extends Runnable & Collection> {}
  class ClassWithClassBoundedTypeParameter<T extends ArrayList> {}
  class ClassWithTypeVarBoundedTypeParameter<T extends U, U extends ArrayList> {}

  // Weird stuff
  class GenericClass<T> {
    class GenericInnerClassInsideGeneric<U> {}
    class NonGenericInnerClassInsideGeneric {}
  }
  GenericClass<Integer>.GenericInnerClassInsideGeneric<String> genericInnerClassInsideGenericField;
  GenericClass<Integer>.NonGenericInnerClassInsideGeneric nonGenericInnerClassInsideGenericField;


}
