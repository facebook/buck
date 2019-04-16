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

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckElementFactory;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionDefinition;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Mixins for {@link com.facebook.buck.intellij.ideabuck.lang.BuckLanguage} elements. */
public class BuckPsiImplUtil {

  // BuckArgument mixins
  /** See {@link PsiNameIdentifierOwner#getName()} */
  @Nullable
  public static String getName(BuckArgument argument) {
    BuckIdentifier identifier = argument.getIdentifier();
    return (identifier == null) ? null : getName(identifier);
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BuckArgument argument) {
    BuckIdentifier identifier = argument.getIdentifier();
    return (identifier == null) ? null : getNameIdentifier(identifier);
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static BuckArgument setName(BuckArgument argument, @NotNull String newName) {
    setName(argument.getIdentifier(), newName);
    return argument;
  }

  // BuckFunctionDefinition mixins

  /** See {@link PsiNameIdentifierOwner#getName()} */
  public static String getName(BuckFunctionDefinition buckFunctionDefinition) {
    return buckFunctionDefinition.getIdentifier().getName();
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BuckFunctionDefinition buckFunctionDefinition) {
    return buckFunctionDefinition.getIdentifier().getNameIdentifier();
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static BuckFunctionDefinition setName(
      BuckFunctionDefinition buckFunctionDefinition, @NotNull String newName) {
    buckFunctionDefinition.getIdentifier().setName(newName);
    return buckFunctionDefinition;
  }

  // BuckIdentifier mixins

  /** See {@link PsiNameIdentifierOwner#getName()} */
  public static String getName(BuckIdentifier buckIdentifier) {
    return buckIdentifier.getIdentifierToken().getText();
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BuckIdentifier buckIdentifier) {
    return buckIdentifier.getIdentifierToken();
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static BuckIdentifier setName(BuckIdentifier buckIdentifier, @NotNull String newName) {
    BuckIdentifier tempIdentifier =
        BuckElementFactory.createElement(
            buckIdentifier.getProject(), newName, BuckIdentifier.class);
    PsiElement oldToken = buckIdentifier.getIdentifierToken();
    PsiElement newToken = tempIdentifier.getIdentifierToken();
    buckIdentifier.getNode().replaceChild(oldToken.getNode(), newToken.getNode());
    return buckIdentifier;
  }

  // BuckLoadArgument mixins

  /** See {@link PsiNameIdentifierOwner#getName()} */
  public static String getName(BuckLoadArgument buckLoadArgument) {
    return Optional.of(buckLoadArgument)
        .map(BuckLoadArgument::getIdentifier)
        .map(BuckIdentifier::getName)
        .orElse(getValue(buckLoadArgument.getString()));
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BuckLoadArgument buckLoadArgument) {
    return Optional.of(buckLoadArgument)
        .<PsiElement>map(BuckLoadArgument::getIdentifier)
        .orElse(buckLoadArgument.getString());
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static BuckLoadArgument setName(
      BuckLoadArgument buckLoadArgument, @NotNull String newName) {
    Optional<BuckIdentifier> identifier = Optional.ofNullable(buckLoadArgument.getIdentifier());
    if (identifier.isPresent()) {
      identifier.get().setName(newName);
    } else {
      setValue(buckLoadArgument.getString(), newName);
    }
    return buckLoadArgument;
  }

  // BuckString mixins

  /**
   * Return the effective text between a string's opening/closing quotes, applying the appropriate
   * unescaping to the raw content.
   */
  public static String getValue(BuckString buckString) {
    // TODO: fixme to handle escape sequences correctly
    return getInnerText(buckString);
  }

  /** Return the raw text between a string's opening/closing quotes. */
  public static String getInnerText(BuckString buckString) {
    String text = buckString.getText();
    int start;
    int end = text.length();
    if (buckString.getApostrophedRawString() != null) {
      start = 2; // Advance past r'
      if (text.endsWith("'")) {
        end -= 1;
      }
    } else if (buckString.getApostrophedString() != null) {
      start = 1; // Advance past '
      if (text.endsWith("'")) {
        end -= 1;
      }
    } else if (buckString.getQuotedRawString() != null) {
      start = 2; // Advance past r"
      if (text.endsWith("\"")) {
        end -= 1;
      }
    } else if (buckString.getQuotedString() != null) {
      start = 1; // Advance past "
      if (text.endsWith("\"")) {
        end -= 1;
      }
    } else if (buckString.getTripleApostrophedRawString() != null) {
      start = 4; // Advance past r'''
      if (text.endsWith("'''")) {
        end -= 3;
      } else if (text.endsWith("''")) {
        end -= 2;
      } else if (text.endsWith("'")) {
        end -= 1;
      }
    } else if (buckString.getTripleApostrophedString() != null) {
      start = 3; // Advance past '''
      if (text.endsWith("'''")) {
        end -= 3;
      } else if (text.endsWith("''")) {
        end -= 2;
      } else if (text.endsWith("'")) {
        end -= 1;
      }
    } else if (buckString.getTripleQuotedRawString() != null) {
      start = 4; // Advance past r"""
      if (text.endsWith("\"\"\"")) {
        end -= 3;
      } else if (text.endsWith("\"\"")) {
        end -= 2;
      } else if (text.endsWith("\"")) {
        end -= 1;
      }
    } else if (buckString.getTripleQuotedString() != null) {
      start = 3; // Advance past """
      if (text.endsWith("\"\"\"")) {
        end -= 3;
      } else if (text.endsWith("\"\"")) {
        end -= 2;
      } else if (text.endsWith("\"")) {
        end -= 1;
      }
    } else {
      throw new AssertionError("Not one of the eight recognized string types: " + text);
    }
    if (start >= end) {
      return "";
    }
    return text.substring(start, end);
  }

  /**
   * Sets the effective text between a string's opening/closing quotes, applying the appropriate
   * escaping for the given string.
   *
   * <p>Note that not all values can be set for all types of strings. (For example, one cannot
   * include a double-quote character in a raw string that uses double-quotes as delimiter.)
   */
  public static void setValue(BuckString buckString, String newValue) {
    // TODO: fixme to handle escape sequences correctly
    setInnerText(buckString, newValue);
  }

  /**
   * Sets the raw text between a string's opening/closing quotes.
   *
   * <p>No error-checking is done; the caller is responsible for making sure the content is properly
   * escaped for the given type of string
   */
  public static void setInnerText(BuckString buckString, String newValue) {
    // TODO: sanity check the newValue to see if it needs to be escaped
    PsiElement oldStringChild;
    PsiElement newStringChild = null;
    if ((oldStringChild = buckString.getApostrophedRawString()) != null) {
      setText(buckString, "r'" + newValue + "'");
    } else if ((oldStringChild = buckString.getApostrophedString()) != null) {
      setText(buckString, "'" + newValue + "'");
    } else if ((oldStringChild = buckString.getQuotedRawString()) != null) {
      setText(buckString, "r\"" + newValue + "\"");
    } else if ((oldStringChild = buckString.getQuotedString()) != null) {
      setText(buckString, "\"" + newValue + "\"");
    } else if ((oldStringChild = buckString.getTripleApostrophedRawString()) != null) {
      setText(buckString, "r'''" + newValue + "'''");
    } else if ((oldStringChild = buckString.getTripleApostrophedString()) != null) {
      setText(buckString, "'''" + newValue + "'''");
    } else if ((oldStringChild = buckString.getTripleQuotedRawString()) != null) {
      setText(buckString, "r\"\"\"" + newValue + "\"\"\"");
    } else if ((oldStringChild = buckString.getTripleQuotedString()) != null) {
      setText(buckString, "\"\"\"" + newValue + "\"\"\"");
    }
    if (oldStringChild != null && newStringChild != null) {
      buckString.getNode().replaceChild(oldStringChild.getNode(), newStringChild.getNode());
    }
  }

  /** Sets the contents of a string, including delimiter. */
  public static BuckString setText(BuckString buckString, String newText) {
    BuckString newString =
        BuckElementFactory.createElement(buckString.getProject(), newText, BuckString.class);
    buckString.getNode().replaceAllChildrenToChildrenOf(newString.getNode());
    return buckString;
  }
}
