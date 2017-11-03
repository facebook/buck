// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import it.unimi.dsi.fastutil.objects.Reference2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Extracts the ordinal values for all Enum classes from their static initializer.
 * <p>
 * An Enum class has a field for each value. In the class initializer, each field is initialized
 * to a singleton object that represents the value. This code matches on the corresponding call
 * to the constructor (instance initializer) and extracts the value of the second argument, which
 * is the ordinal.
 */
public class EnumOrdinalMapCollector {

  private final AppInfoWithLiveness appInfo;
  private final InternalOptions options;

  private final Map<DexType, Reference2IntMap<DexField>> ordinalsMaps = new IdentityHashMap<>();

  public EnumOrdinalMapCollector(AppInfoWithLiveness appInfo, InternalOptions options) {
    this.appInfo = appInfo;
    this.options = options;
  }

  public static Reference2IntMap<DexField> getOrdinalsMapFor(DexType enumClass,
      AppInfoWithLiveness appInfo) {
    Map<DexType, Reference2IntMap<DexField>> ordinalsMaps = appInfo
        .getExtension(EnumOrdinalMapCollector.class, Collections.emptyMap());
    return ordinalsMaps.get(enumClass);
  }

  public void run() throws ApiLevelException {
    for (DexProgramClass clazz : appInfo.classes()) {
      processClasses(clazz);
    }
    if (!ordinalsMaps.isEmpty()) {
      appInfo.setExtension(EnumOrdinalMapCollector.class, ordinalsMaps);
    }
  }

  private void processClasses(DexProgramClass clazz) throws ApiLevelException {
    // Enum classes are flagged as such. Also, for library classes, the ordinals are not known.
    if (!clazz.accessFlags.isEnum() || clazz.isLibraryClass() || !clazz.hasClassInitializer()) {
      return;
    }
    DexEncodedMethod initializer = clazz.getClassInitializer();
    IRCode code = initializer.getCode().buildIR(initializer, options);
    Reference2IntMap<DexField> ordinalsMap = new Reference2IntArrayMap<>();
    ordinalsMap.defaultReturnValue(-1);
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      Instruction insn = it.next();
      if (!insn.isStaticPut()) {
        continue;
      }
      StaticPut staticPut = insn.asStaticPut();
      if (staticPut.getField().type != clazz.type) {
        continue;
      }
      Instruction newInstance = staticPut.inValue().definition;
      if (newInstance == null || !newInstance.isNewInstance()) {
        continue;
      }
      Instruction ordinal = null;
      for (Instruction ctorCall : newInstance.outValue().uniqueUsers()) {
        if (!ctorCall.isInvokeDirect()) {
          continue;
        }
        InvokeDirect invoke = ctorCall.asInvokeDirect();
        if (!appInfo.dexItemFactory.isConstructor(invoke.getInvokedMethod())
            || invoke.arguments().size() < 3) {
          continue;
        }
        ordinal = invoke.arguments().get(2).definition;
        break;
      }
      if (ordinal == null || !ordinal.isConstNumber()) {
        return;
      }
      if (ordinalsMap.put(staticPut.getField(), ordinal.asConstNumber().getIntValue()) != -1) {
        return;
      }
    }
    ordinalsMaps.put(clazz.type, ordinalsMap);
  }
}
