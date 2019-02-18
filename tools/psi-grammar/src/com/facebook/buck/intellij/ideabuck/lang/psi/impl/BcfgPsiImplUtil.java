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

import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgElementFactory;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgInline;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgProperty;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgPropertyName;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgPropertyValue;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgSection;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgSectionName;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTypes;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/** Mixins for {@link com.facebook.buck.intellij.ideabuck.lang.BcfgLanguage} elements. */
public class BcfgPsiImplUtil {

  /* White space elements are significant *except* for line continuations. */
  private static boolean isSignificantWhitespace(PsiElement element) {
    return TokenType.WHITE_SPACE.equals(element.getNode().getElementType())
        && !element.getText().startsWith("\\");
  }

  // BcfgInline mixins

  /** Returns true if the inlined file is required. */
  public static boolean isRequired(BcfgInline inline) {
    PsiElement requiredField = inline.getFirstChild();
    IElementType elementType = requiredField.getNode().getElementType();
    if (elementType.equals(BcfgTypes.REQUIRED_FILE)) {
      return true;
    } else if (elementType.equals(BcfgTypes.OPTIONAL_FILE)) {
      return false;
    } else {
      return false; // How could this be a BcfgInline?
    }
  }

  /**
   * Returns the inlined file as a virtual file, if it can be found.
   *
   * <p>Note that this is not a guarantee that the file exists.
   */
  public static Optional<VirtualFile> getVirtualFile(BcfgInline inline) {
    return Optional.of(inline)
        .map(BcfgInline::getContainingFile)
        .map(PsiFile::getVirtualFile)
        .map(VirtualFile::getParent)
        .map(virtualFile -> virtualFile.findFileByRelativePath(inline.getFilePath().getText()));
  }

  /**
   * Returns the inlined file as a path. If the contents of the inline declaration cannot be parsed,
   * return {@link Optional#empty()}. If if can be parsed/resolved, this will return the path,
   * whether or not it actually exists.
   */
  public static Optional<Path> getPath(BcfgInline inline) {
    return Optional.of(inline)
        .map(BcfgInline::getContainingFile)
        .map(PsiFile::getVirtualFile)
        .map(VirtualFile::getPath)
        .map(Paths::get)
        .map(path -> path.resolveSibling(inline.getFilePath().getText()));
  }

  // BcfgSection mixins

  /** See {@link PsiNameIdentifierOwner#getName()} */
  public static String getName(BcfgSection section) {
    return getValue(section.getSectionHeader().getSectionName());
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BcfgSection section) {
    return section.getSectionHeader().getSectionName();
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static PsiElement setName(BcfgSection section, @NotNull String newName) {
    BcfgSection copy = BcfgElementFactory.createSection(section.getProject(), newName);
    PsiElement oldSectionName = section.getSectionHeader().getSectionName();
    PsiElement newSectionName = copy.getSectionHeader().getSectionName();
    section.getNode().replaceChild(oldSectionName.getNode(), newSectionName.getNode());
    return section;
  }

  // BcfgSectionName mixins

  /**
   * Returns a list of the fragments of a {@link BcfgSectionName} that can be concatenated to get
   * its textual name.
   */
  public static List<PsiElement> getFragments(BcfgSectionName sectionName) {
    // Don't be fooled into calling "getChildren()", because that only returns compound children.
    List<PsiElement> results = new ArrayList<>();
    PsiElement child = sectionName.getFirstChild();
    int lengthToReturn = 0;
    while (child != null) {
      if (child.getNode().getElementType() == BcfgTypes.SECTION_NAME_FRAGMENT) {
        results.add(child);
        lengthToReturn = results.size();
      } else if (lengthToReturn > 0 && isSignificantWhitespace(child)) {
        results.add(child); // collect, but don't increment lengthToReturn
      }
      child = child.getNextSibling();
    }
    return results.subList(0, lengthToReturn);
  }

  /**
   * Returns a list of the fragments of a {@link BcfgSectionName} that can be concatenated to get
   * its textual name.
   */
  public static String getValue(BcfgSectionName sectionName) {
    return getFragments(sectionName)
        .stream()
        .map(PsiElement::getText)
        .collect(Collectors.joining());
  }

  // BcfgProperty mixins

  /** Returns the parent section of this property. */
  public static BcfgSection getSection(BcfgProperty property) {
    return PsiTreeUtil.getParentOfType(property, BcfgSection.class);
  }

  /** See {@link PsiNameIdentifierOwner#getName()} */
  public static String getName(BcfgProperty property) {
    return getValue(property.getPropertyName());
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BcfgProperty property) {
    return property.getPropertyName();
  }

  /**
   * Returns the property value of a given {@link BcfgProperty} as a string, including significant
   * inner whitespace (i.e, not line continuations) but not expanding escape sequences or macros.
   */
  public static String getValue(BcfgProperty property) {
    return getValue(property.getPropertyValue());
  }

  /** Returns the section name for this property. */
  public static String getSectionName(BcfgProperty property) {
    return getName(getSection(property));
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static PsiElement setName(BcfgProperty property, @NotNull String newName) {
    BcfgProperty copy =
        BcfgElementFactory.createProperty(
            property.getProject(), getSectionName(property), newName, "");
    PsiElement oldPropertyName = property.getPropertyName();
    PsiElement newPropertyName = copy.getPropertyName();
    property.getNode().replaceChild(oldPropertyName.getNode(), newPropertyName.getNode());
    return property;
  }
  // BcfgPropertyName mixins

  /**
   * Returns a list of the fragments of a {@link BcfgPropertyName} that can be concatenated to get
   * its textual name.
   */
  public static List<PsiElement> getFragments(BcfgPropertyName propertyName) {
    // Don't be fooled into calling "getChildren()", because that only returns compound children.
    List<PsiElement> results = new ArrayList<>();
    PsiElement child = propertyName.getFirstChild();
    int lengthToReturn = 0;
    while (child != null) {
      if (child.getNode().getElementType() == BcfgTypes.PROPERTY_NAME_FRAGMENT) {
        results.add(child);
        lengthToReturn = results.size();
      } else if (lengthToReturn > 0 && isSignificantWhitespace(child)) {
        results.add(child); // collect, but don't increment lengthToReturn
      }
      child = child.getNextSibling();
    }
    return results.subList(0, lengthToReturn);
  }

  /**
   * Returns the property name for a given {@link BcfgPropertyName} as a string, including
   * significant inner whitespace (i.e, not line continuations).
   */
  public static String getValue(BcfgPropertyName propertyName) {
    return getFragments(propertyName)
        .stream()
        .map(PsiElement::getText)
        .collect(Collectors.joining());
  }

  // BcfgPropertyValue mixins

  /**
   * Returns a list of the fragments of a {@link BcfgPropertyValue} that can be concatenated to get
   * its textual value.
   *
   * <p>This includes (significant) whitespace, but does not expand macros.
   */
  public static List<PsiElement> getFragments(BcfgPropertyValue propertyValue) {
    // Don't be fooled into calling "getChildren()", because that only returns compound children.
    List<PsiElement> results = new ArrayList<>();
    PsiElement child = propertyValue.getFirstChild();
    int lengthToReturn = 0;
    while (child != null) {
      if (child.getNode().getElementType() == BcfgTypes.PROPERTY_VALUE_FRAGMENT) {
        results.add(child);
        lengthToReturn = results.size();
      } else if (lengthToReturn > 0 && isSignificantWhitespace(child)) {
        results.add(child); // collect, but don't increment lengthToReturn
      }
      child = child.getNextSibling();
    }
    return results.subList(0, lengthToReturn);
  }

  /**
   * Returns the property value for a given {@link BcfgPropertyValue} as a string, including
   * significant inner whitespace (i.e, not line continuations) but not expanding escape sequences
   * or macros.
   */
  public static String getValue(BcfgPropertyValue propertyValue) {
    return getFragments(propertyValue)
        .stream()
        .map(PsiElement::getText)
        .collect(Collectors.joining());
  }
}
