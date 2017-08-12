// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.code.ConstString;
import com.android.tools.r8.code.ConstStringJumbo;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.shaking.ProguardClassFilter;
import com.android.tools.r8.utils.DescriptorUtils;
import java.util.Map;

class IdentifierMinifier {

  private final AppInfo appInfo;
  private final ProguardClassFilter adaptClassStrings;
  private final NamingLens lens;

  IdentifierMinifier(
      AppInfo appInfo,
      ProguardClassFilter adaptClassStrings,
      NamingLens lens) {
    this.appInfo = appInfo;
    this.adaptClassStrings = adaptClassStrings;
    this.lens = lens;
  }

  void run() {
    if (!adaptClassStrings.isEmpty()) {
      handleAdaptClassStrings();
    }
    // TODO(b/36799092): Handle influx of string literals from call sites to annotated members.
  }

  private void handleAdaptClassStrings() {
    appInfo.classes().forEach(clazz -> {
      if (!adaptClassStrings.matches(clazz.type)) {
        return;
      }
      clazz.forEachField(encodedField -> {
        if (encodedField.staticValue instanceof DexValueString) {
          DexString original = ((DexValueString) encodedField.staticValue).getValue();
          DexString renamed = getRenamedStringLiteral(original);
          if (renamed != original) {
            encodedField.staticValue = new DexValueString(renamed);
          }
        }
      });
      clazz.forEachMethod(encodedMethod -> {
        // Abstract methods do not have code_item.
        if (encodedMethod.accessFlags.isAbstract()) {
          return;
        }
        Code code = encodedMethod.getCode();
        if (code == null) {
          return;
        }
        assert code.isDexCode();
        DexCode dexCode = code.asDexCode();
        for (Instruction instr : dexCode.instructions) {
          if (instr instanceof ConstString) {
            ConstString cnst = (ConstString) instr;
            DexString dexString = cnst.getString();
            cnst.BBBB = getRenamedStringLiteral(dexString);
          } else if (instr instanceof ConstStringJumbo) {
            ConstStringJumbo cnst = (ConstStringJumbo) instr;
            DexString dexString = cnst.getString();
            cnst.BBBBBBBB = getRenamedStringLiteral(dexString);
          }
        }
      });
    });
  }

  private DexString getRenamedStringLiteral(DexString originalLiteral) {
    String originalString = originalLiteral.toString();
    Map<String, DexType> renamedYetMatchedTypes =
        lens.getRenamedItems(
            DexType.class,
            type -> type.toSourceString().equals(originalString),
            DexType::toSourceString);
    DexType type = renamedYetMatchedTypes.get(originalString);
    if (type != null) {
      DexString renamed = lens.lookupDescriptor(type);
      // Create a new DexString only when the corresponding string literal will be replaced.
      if (renamed != originalLiteral) {
        return appInfo.dexItemFactory.createString(
            DescriptorUtils.descriptorToJavaType(renamed.toString()));
      }
    }
    return originalLiteral;
  }

}
