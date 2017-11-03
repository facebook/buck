// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexEncodedMethod.DebugPositionRange;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.utils.DescriptorUtils;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class MinifiedNameMapPrinter {

  private final DexApplication application;
  private final NamingLens namingLens;
  private final Set<DexType> seenTypes = Sets.newIdentityHashSet();

  public MinifiedNameMapPrinter(DexApplication application, NamingLens namingLens) {
    this.application = application;
    this.namingLens = namingLens;
  }

  private <T> T[] sortedCopy(T[] source, Comparator<? super T> comparator) {
    T copy[] = Arrays.copyOf(source, source.length);
    Arrays.sort(copy, comparator);
    return copy;
  }

  private void writeClass(DexProgramClass clazz, PrintStream out) {
    seenTypes.add(clazz.type);
    DexString descriptor = namingLens.lookupDescriptor(clazz.type);
    out.print(DescriptorUtils.descriptorToJavaType(clazz.type.descriptor.toSourceString()));
    out.print(" -> ");
    out.print(DescriptorUtils.descriptorToJavaType(descriptor.toSourceString()));
    out.println(":");
    writeFields(sortedCopy(
        clazz.instanceFields(), Comparator.comparing(DexEncodedField::toSourceString)), out);
    writeFields(sortedCopy(
        clazz.staticFields(), Comparator.comparing(DexEncodedField::toSourceString)), out);
    writeMethods(sortedCopy(
        clazz.directMethods(), Comparator.comparing(DexEncodedMethod::toSourceString)), out);
    writeMethods(sortedCopy(
        clazz.virtualMethods(), Comparator.comparing(DexEncodedMethod::toSourceString)), out);
  }

  private void writeType(DexType type, PrintStream out) {
    if (type.isClassType() && seenTypes.add(type)) {
      DexString descriptor = namingLens.lookupDescriptor(type);
      out.print(DescriptorUtils.descriptorToJavaType(type.descriptor.toSourceString()));
      out.print(" -> ");
      out.print(DescriptorUtils.descriptorToJavaType(descriptor.toSourceString()));
      out.println(":");
    }
  }

  private void writeFields(DexEncodedField[] fields, PrintStream out) {
    for (DexEncodedField encodedField : fields) {
      DexField field = encodedField.field;
      DexString renamed = namingLens.lookupName(field);
      if (renamed != field.name) {
        out.print("    ");
        out.print(field.type.toSourceString());
        out.print(" ");
        out.print(field.name.toSourceString());
        out.print(" -> ");
        out.println(renamed.toSourceString());
      }
    }
  }

  private void writeMethod(
      MethodSignature signature,
      String renamed,
      PrintStream out,
      DexEncodedMethod.DebugPositionRange range) {
    out.print("    ");
    if (range != null) {
      out.printf("%d:%d:", range.emittedFirst, range.getEmittedLast());
    }
    out.print(signature);
    if (range != null && range.originalFirst != range.emittedFirst) {
      out.printf(":%d:%d", range.originalFirst, range.getOriginalLast());
    }
    out.print(" -> ");
    out.println(renamed);
  }

  private void writeMethods(DexEncodedMethod[] methods, PrintStream out) {
    for (DexEncodedMethod encodedMethod : methods) {
      DexMethod method = encodedMethod.method;
      DexString renamed = namingLens.lookupName(method);
      if (renamed != method.name) {
        MethodSignature signature = MethodSignature.fromDexMethod(method);
        String renamedSourceString = renamed.toSourceString();
        if (encodedMethod.debugPositionRangeList == null
            || encodedMethod.debugPositionRangeList.isEmpty()) {
          writeMethod(signature, renamedSourceString, out, null);
        } else {
          for (DebugPositionRange range : encodedMethod.debugPositionRangeList) {
            writeMethod(signature, renamedSourceString, out, range);
          }
        }
      }
    }
  }

  public void write(PrintStream out) {
    // First write out all classes that have been renamed.
    List<DexProgramClass> classes = new ArrayList<>(application.classes());
    classes.sort(Comparator.comparing(DexProgramClass::toSourceString));
    classes.forEach(clazz -> writeClass(clazz, out));
    // Now write out all types only mentioned in descriptors that have been renamed.
    namingLens.forAllRenamedTypes(type -> writeType(type, out));
  }

  public void write(Path destination) throws IOException {
    PrintStream out = new PrintStream(Files.newOutputStream(destination), true,
        StandardCharsets.UTF_8.name());
    write(out);
  }

}
