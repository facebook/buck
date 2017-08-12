// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.shaking.FilteredClassPath;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.AndroidApp.Builder;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.Timing;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrintClassList {

  public static void main(String[] args) throws IOException, ExecutionException {
    List<String> dexFiles = Arrays.asList(args);
    Builder builder = AndroidApp.builder();
    if (args[0].endsWith("map")) {
      builder.setProguardMapFile(Paths.get(args[0]));
      dexFiles = dexFiles.subList(1, dexFiles.size());
    }
    builder.addProgramFiles(ListUtils.map(dexFiles, FilteredClassPath::unfiltered));

    ExecutorService executorService = Executors.newCachedThreadPool();
    DexApplication application =
        new ApplicationReader(builder.build(), new InternalOptions(), new Timing("PrintClassList"))
            .read(executorService);
    ClassNameMapper map = application.getProguardMap();
    for (DexProgramClass clazz : application.classes()) {
      System.out.print(maybeDeobfuscateType(map, clazz.type));
      System.out.println();
      clazz.forEachMethod(method -> printMethod(method, map));
      clazz.forEachField(field -> printField(field, map));
    }
    executorService.shutdown();
  }

  private static void printMethod(DexEncodedMethod encodedMethod, ClassNameMapper map) {
    DexMethod method = encodedMethod.method;
    if (map != null) {
      System.out.println(map.originalNameOf(method));
    } else {
      // Detour via Signature to get the same formatting.
      MethodSignature signature = MethodSignature.fromDexMethod(method);
      System.out.println(method.holder.toSourceString() + " " + signature);
    }
  }

  private static void printField(DexEncodedField encodedField, ClassNameMapper map) {
    DexField field = encodedField.field;
    if (map != null) {
      System.out.println(map.originalNameOf(field));
    } else {
      // Detour via Signature to get the same formatting.
      FieldSignature signature = new FieldSignature(field.name.toSourceString(),
          field.type.toSourceString());
      System.out.println(field.clazz.toSourceString() + " " + signature);
    }
  }

  private static String maybeDeobfuscateType(ClassNameMapper map, DexType type) {
    return map == null ? type.toSourceString() : map.originalNameOf(type);
  }
}
