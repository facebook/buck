package com.android.tools.r8.graph;

import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.utils.ProgramResource;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Kind of the application class. Can be program, classpath or library. */
public enum ClassKind {
  PROGRAM(DexProgramClass::new, DexClass::isProgramClass),
  CLASSPATH(DexClasspathClass::new, DexClass::isClasspathClass),
  LIBRARY(DexLibraryClass::new, DexClass::isLibraryClass);

  private interface Factory {
    DexClass create(
        DexType type,
        ProgramResource.Kind kind,
        Origin origin,
        ClassAccessFlags accessFlags,
        DexType superType,
        DexTypeList interfaces,
        DexString sourceFile,
        DexAnnotationSet annotations,
        DexEncodedField[] staticFields,
        DexEncodedField[] instanceFields,
        DexEncodedMethod[] directMethods,
        DexEncodedMethod[] virtualMethods);
  }

  private final Factory factory;
  private final Predicate<DexClass> check;

  ClassKind(Factory factory, Predicate<DexClass> check) {
    this.factory = factory;
    this.check = check;
  }

  public DexClass create(
      DexType type,
      ProgramResource.Kind kind,
      Origin origin,
      ClassAccessFlags accessFlags,
      DexType superType,
      DexTypeList interfaces,
      DexString sourceFile,
      DexAnnotationSet annotations,
      DexEncodedField[] staticFields,
      DexEncodedField[] instanceFields,
      DexEncodedMethod[] directMethods,
      DexEncodedMethod[] virtualMethods) {
    return factory.create(
        type,
        kind,
        origin,
        accessFlags,
        superType,
        interfaces,
        sourceFile,
        annotations,
        staticFields,
        instanceFields,
        directMethods,
        virtualMethods);
  }

  public boolean isOfKind(DexClass clazz) {
    return check.test(clazz);
  }

  public <T extends DexClass> Consumer<DexClass> bridgeConsumer(Consumer<T> consumer) {
    return clazz -> {
      assert isOfKind(clazz);
      @SuppressWarnings("unchecked") T specialized = (T) clazz;
      consumer.accept(specialized);
    };
  }
}
