package com.facebook.foo;

import java.util.Collection;
import java.util.List;
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
  Dependency topLevelClassField;
  Dependency.Inner nestedClassField;
  Dependency[] objectArrayField;
  Dependency<String> genericField;
  List<Dependency> genericField2;
  Dependency<?> wildcardField;
  Dependency<? super String> superWildcardField;
  List<? super Dependency> superWildcardField2;
  Dependency<? extends Runnable> extendsWildcardField;
  List<? extends Dependency> extendsWildcardField2;
  List<Dependency<String>> nestedGenericField;
  List<Set<Dependency>> nestedGenericField2;
  Dependency<String>[] genericArrayField;
  Dependency[][] multiDimObjectArrayField;

  // Methods
  abstract Dependency parameterlessMethod();

  abstract int methodWithParameters(int p1, int p2);

  abstract void methodWithGenericParams(Dependency<String> a, int b);

  abstract void methodWithGenericParams2(List<Dependency> a, int b);

  abstract <X, Y, Z> Z methodWithTypeVarParameters(X x, List<Y> y, Set<? extends Z> z);

  abstract String parameterlessThrowingMethod() throws DependencyException;

  abstract <T extends Exception> void typevarThrowingMethod() throws T, Exception;

  abstract <T extends Exception> void nonGenericThrowingGenericMethod() throws Exception;

  abstract Dependency<String> genericReturningMethod();

  abstract List<Dependency> genericReturningMethod2();

  // Type vars
  abstract <T extends Dependency & DependencyInterface> void typeVarWithClassAndInterfaceBounds(
      T t);

  abstract <T extends Runnable & DependencyInterface> void typeVarWithMultipleBounds(T t);

  abstract <T extends DependencyInterface & Runnable> void typeVarWithDifferentOrderBounds(T t);

  // Classes
  class NonGenericClass {}

  class SimpleGenericClass<T> {
    class InnerClass<U> {
      InnerClass<U> innerInnerClassWithImplicitEnclosingTypes;
    }
  }

  interface SimpleGenericInterface<T> {}

  abstract class ClassWithTypeVariableParameterizedSuper<T> extends Dependency<T> {}

  abstract class ClassWithParameterizedSuper extends Dependency<Integer> {}

  abstract class ClassWithParameterizedInterfaces
      implements DependencyInterface<Integer>, Comparable<Dependency> {}

  class ClassWithInterfaceBoundedTypeParameter<T extends DependencyInterface & Collection> {}

  class ClassWithClassBoundedTypeParameter<T extends Dependency> {}

  class ClassWithTypeVarBoundedTypeParameter<T extends U, U extends Runnable> {}

  // Weird stuff
  Dependency<Integer>.NonGenericInner.GenericInnerer<String>
      genericInnerClassInsideNonGenericInsideGenericField;
  Dependency<Integer>.GenericInner<String> genericInnerClassInsideGenericField;
  Dependency<Integer>.NonGenericInner nonGenericInnerClassInsideGenericField;

  SimpleGenericClass<Dependency>.InnerClass<String> enclosingInstanceWithErrorTypeArgs;
  SimpleGenericClass<String>.InnerClass<Dependency> innerClassWithErrorTypeArgs;
}
