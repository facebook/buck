/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.intellij.ideabuck.lang.psi.impl;

import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgProperty;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTypes;
import com.intellij.psi.PsiElement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility methods for extracting information from {@link
 * com.facebook.buck.intellij.ideabuck.lang.BcfgLanguage} elements.
 */
public class BcfgPsiImplUtil {

  /** Returns a list of the property value fragments for a given {@link BcfgProperty}. */
  public static List<PsiElement> getPropertyValueFragmentList(BcfgProperty property) {
    // Don't be fooled into calling "getChildren()", because that only returns compound children.
    List<PsiElement> results = new ArrayList<>();
    PsiElement child = property.getFirstChild();
    while (child != null) {
      if (child.getNode().getElementType() == BcfgTypes.PROPERTY_VALUE_FRAGMENT) {
        results.add(child);
      }
      child = child.getNextSibling();
    }
    return results;
  }

  /**
   * Returns the property value for a given {@link BcfgProperty} as a string, omitting inner line
   * continuations but not expanding escape sequences or macros.
   */
  public static String getPropertyValueAsText(BcfgProperty property) {
    return getPropertyValueFragmentList(property)
        .stream()
        .map(PsiElement::getText)
        .collect(Collectors.joining());
  }
}
