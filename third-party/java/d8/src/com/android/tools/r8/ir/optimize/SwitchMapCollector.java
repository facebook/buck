// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import it.unimi.dsi.fastutil.ints.Int2ReferenceArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Extracts the mapping from ordinal values to switch case constants.
 * <p>
 * This is done by pattern-matching on the class initializer of the synthetic switch map class.
 * For a switch
 *
 * <blockquote><pre>
 * switch (day) {
 *   case WEDNESDAY:
 *   case FRIDAY:
 *     System.out.println("3 or 5");
 *     break;
 *   case SUNDAY:
 *     System.out.println("7");
 *     break;
 *   default:
 *     System.out.println("other");
 * }
 * </pre></blockquote>
 *
 * the generated companing class initializer will have the form
 *
 * <blockquote><pre>
 * class Switches$1 {
 *   static {
 *   $SwitchMap$switchmaps$Days[Days.WEDNESDAY.ordinal()] = 1;
 *   $SwitchMap$switchmaps$Days[Days.FRIDAY.ordinal()] = 2;
 *   $SwitchMap$switchmaps$Days[Days.SUNDAY.ordinal()] = 3;
 * }
 * </pre></blockquote>
 *
 * Note that one map per class is generated, so the map might contain additional entries as used
 * by other switches in the class.
 */
public class SwitchMapCollector {

  private final AppInfoWithLiveness appInfo;
  private final InternalOptions options;
  private final DexString switchMapPrefix;
  private final DexType intArrayType;

  private final Map<DexField, Int2ReferenceMap<DexField>> switchMaps = new IdentityHashMap<>();

  public SwitchMapCollector(AppInfoWithLiveness appInfo, InternalOptions options) {
    this.appInfo = appInfo;
    this.options = options;
    switchMapPrefix = appInfo.dexItemFactory.createString("$SwitchMap$");
    intArrayType = appInfo.dexItemFactory.createType("[I");
  }

  public void run() throws ApiLevelException {
    for (DexProgramClass clazz : appInfo.classes()) {
      processClasses(clazz);
    }
    if (!switchMaps.isEmpty()) {
      appInfo.setExtension(SwitchMapCollector.class, switchMaps);
    }
  }

  public static Int2ReferenceMap<DexField> getSwitchMapFor(DexField field,
      AppInfoWithLiveness appInfo) {
    Map<DexField, Int2ReferenceMap<DexField>> switchMaps = appInfo
        .getExtension(SwitchMapCollector.class, Collections.emptyMap());
    return switchMaps.get(field);
  }

  private void processClasses(DexProgramClass clazz) throws ApiLevelException {
    // Switchmap classes are synthetic and have a class initializer.
    if (!clazz.accessFlags.isSynthetic() && !clazz.hasClassInitializer()) {
      return;
    }
    List<DexEncodedField> switchMapFields = Arrays.stream(clazz.staticFields())
        .filter(this::maybeIsSwitchMap).collect(Collectors.toList());
    if (!switchMapFields.isEmpty()) {
      IRCode initializer = clazz.getClassInitializer().buildIR(options);
      switchMapFields.forEach(field -> extractSwitchMap(field, initializer));
    }
  }

  private void extractSwitchMap(DexEncodedField encodedField, IRCode initializer) {
    DexField field = encodedField.field;
    Int2ReferenceMap<DexField> switchMap = new Int2ReferenceArrayMap<>();
    InstructionIterator it = initializer.instructionIterator();
    Instruction insn;
    Predicate<Instruction> predicate = i -> i.isStaticGet() && i.asStaticGet().getField() == field;
    while ((insn = it.nextUntil(predicate)) != null) {
      for (Instruction use : insn.outValue().uniqueUsers()) {
        if (use.isArrayPut()) {
          Instruction index = use.asArrayPut().source().definition;
          if (index == null || !index.isConstNumber()) {
            return;
          }
          int integerIndex = index.asConstNumber().getIntValue();
          Instruction value = use.asArrayPut().index().definition;
          if (value == null || !value.isInvokeVirtual()) {
            return;
          }
          InvokeVirtual invoke = value.asInvokeVirtual();
          DexClass holder = appInfo.definitionFor(invoke.getInvokedMethod().holder);
          if (holder == null ||
              (!holder.accessFlags.isEnum() && holder.type != appInfo.dexItemFactory.enumType)) {
            return;
          }
          Instruction enumGet = invoke.arguments().get(0).definition;
          if (enumGet == null || !enumGet.isStaticGet()) {
            return;
          }
          DexField enumField = enumGet.asStaticGet().getField();
          if (!appInfo.definitionFor(enumField.getHolder()).accessFlags.isEnum()) {
            return;
          }
          if (switchMap.put(integerIndex, enumField) != null) {
            return;
          }
        } else {
          return;
        }
      }
    }
    switchMaps.put(field, switchMap);
  }

  private boolean maybeIsSwitchMap(DexEncodedField dexEncodedField) {
    // We are looking for synthetic fields of type int[].
    DexField field = dexEncodedField.field;
    return dexEncodedField.accessFlags.isSynthetic()
        && field.name.beginsWith(switchMapPrefix)
        && field.type == intArrayType;
  }
}
