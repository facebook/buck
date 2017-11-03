// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.CompilationError;
import com.google.common.collect.ImmutableList;
import java.util.List;

public class ProguardKeepAttributes {

  public static final String SOURCE_FILE = "SourceFile";
  public static final String SOURCE_DIR = "SourceDir";
  public static final String INNER_CLASSES = "InnerClasses";
  public static final String ENCLOSING_METHOD = "EnclosingMethod";
  public static final String SIGNATURE = "Signature";
  public static final String EXCEPTIONS = "Exceptions";
  public static final String SOURCE_DEBUG_EXTENSION = "SourceDebugExtension";
  public static final String RUNTIME_VISIBLE_ANNOTATIONS = "RuntimeVisibleAnnotations";
  public static final String RUNTIME_INVISIBLE_ANNOTATIONS = "RuntimeInvisibleAnnotations";
  public static final String RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS =
      "RuntimeVisibleParameterAnnotations";
  public static final String RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS =
      "RuntimeInvisibleParameterAnnotations";
  public static final String RUNTIME_VISIBLE_TYPE_ANNOTATIONS = "RuntimeVisibleTypeAnnotations";
  public static final String RUNTIME_INVISIBLE_TYPE_ANNOTATIONS =
      "RuntimeInvisibleTypeAnnotations";
  public static final String ANNOTATION_DEFAULT = "AnnotationDefault";

  public static final List<String> KEEP_ALL = ImmutableList.of("*");

  public boolean sourceFile = false;
  public boolean sourceDir = false;
  public boolean innerClasses = false;
  public boolean enclosingMethod = false;
  public boolean signature = false;
  public boolean exceptions = false;
  public boolean sourceDebugExtension = false;
  public boolean runtimeVisibleAnnotations = false;
  public boolean runtimeInvisibleAnnotations = false;
  public boolean runtimeVisibleParameterAnnotations = false;
  public boolean runtimeInvisibleParameterAnnotations = false;
  public boolean runtimeVisibleTypeAnnotations = false;
  public boolean runtimeInvisibleTypeAnnotations = false;
  public boolean annotationDefault = false;

  private ProguardKeepAttributes() {
  }

  public static ProguardKeepAttributes filterOnlySignatures() {
    ProguardKeepAttributes result = new ProguardKeepAttributes();
    result.applyPatterns(KEEP_ALL);
    result.signature = false;
    return result;
  }

  /**
   * Implements ProGuards attribute matching rules.
   *
   * @see <a href="https://www.guardsquare.com/en/proguard/manual/attributes">ProGuard manual</a>.
   */
  private boolean update(boolean previous, String text, List<String> patterns) {
    for (String pattern : patterns) {
      if (previous) {
        return true;
      }
      if (pattern.charAt(0) == '!') {
        if (matches(pattern, 1, text, 0)) {
          break;
        }
      } else {
        previous = matches(pattern, 0, text, 0);
      }
    }
    return previous;
  }

  private boolean matches(String pattern, int patternPos, String text, int textPos) {
    while (patternPos < pattern.length()) {
      char next = pattern.charAt(patternPos++);
      if (next == '*') {
        while (textPos < text.length()) {
          if (matches(pattern, patternPos, text, textPos++)) {
            return true;
          }
        }
        return patternPos >= pattern.length();
      } else {
        if (textPos >= text.length() || text.charAt(textPos) != next) {
          return false;
        }
        textPos++;
      }
    }
    return textPos == text.length();
  }

  public static ProguardKeepAttributes fromPatterns(List<String> patterns) {
    ProguardKeepAttributes keepAttributes = new ProguardKeepAttributes();
    keepAttributes.applyPatterns(patterns);
    return keepAttributes;
  }

  public void applyPatterns(List<String> patterns) {
    sourceFile = update(sourceFile, SOURCE_FILE, patterns);
    sourceDir = update(sourceDir, SOURCE_DIR, patterns);
    innerClasses = update(innerClasses, INNER_CLASSES, patterns);
    enclosingMethod = update(enclosingMethod, ENCLOSING_METHOD, patterns);
    signature = update(signature, SIGNATURE, patterns);
    exceptions = update(exceptions, EXCEPTIONS, patterns);
    sourceDebugExtension = update(sourceDebugExtension, SOURCE_DEBUG_EXTENSION, patterns);
    runtimeVisibleAnnotations = update(runtimeVisibleAnnotations, RUNTIME_VISIBLE_ANNOTATIONS,
        patterns);
    runtimeInvisibleAnnotations = update(runtimeInvisibleAnnotations,
        RUNTIME_INVISIBLE_ANNOTATIONS, patterns);
    runtimeVisibleParameterAnnotations = update(runtimeVisibleParameterAnnotations,
        RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS, patterns);
    runtimeInvisibleParameterAnnotations = update(runtimeInvisibleParameterAnnotations,
        RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS, patterns);
    runtimeVisibleTypeAnnotations = update(runtimeVisibleTypeAnnotations,
        RUNTIME_VISIBLE_TYPE_ANNOTATIONS, patterns);
    runtimeInvisibleTypeAnnotations = update(runtimeInvisibleTypeAnnotations,
        RUNTIME_INVISIBLE_TYPE_ANNOTATIONS, patterns);
    annotationDefault = update(annotationDefault, ANNOTATION_DEFAULT, patterns);
  }

  public void ensureValid() {
    if (innerClasses && !enclosingMethod) {
      throw new CompilationError("Attribute InnerClasses requires EnclosingMethod attribute. "
          + "Check -keepattributes directive.");
    } else if (!innerClasses && enclosingMethod) {
      throw new CompilationError("Attribute EnclosingMethod requires InnerClasses attribute. "
          + "Check -keepattributes directive.");
    } else if (signature && !innerClasses) {
      throw new CompilationError("Attribute Signature requires InnerClasses attribute. Check "
          + "-keepattributes directive.");
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProguardKeepAttributes)) {
      return false;
    }
    ProguardKeepAttributes other = (ProguardKeepAttributes) o;
    return this.sourceFile == other.sourceFile
        && this.sourceDir == other.sourceDir
        && this.innerClasses == other.innerClasses
        && this.enclosingMethod == other.enclosingMethod
        && this.signature == other.signature
        && this.exceptions == other.exceptions
        && this.sourceDebugExtension == other.sourceDebugExtension
        && this.runtimeVisibleAnnotations == other.runtimeVisibleAnnotations
        && this.runtimeInvisibleAnnotations == other.runtimeInvisibleAnnotations
        && this.runtimeVisibleParameterAnnotations == other.runtimeVisibleParameterAnnotations
        && this.runtimeInvisibleParameterAnnotations == other.runtimeInvisibleParameterAnnotations
        && this.runtimeVisibleTypeAnnotations == other.runtimeVisibleTypeAnnotations
        && this.runtimeInvisibleTypeAnnotations == other.runtimeInvisibleTypeAnnotations
        && this.annotationDefault == other.annotationDefault;
  }

  @Override
  public int hashCode() {
    return (this.sourceFile ? 1 : 0)
        + (this.sourceDir ? 1 << 1 : 0)
        + (this.innerClasses ? 1 << 2 : 0)
        + (this.enclosingMethod ? 1 << 3 : 0)
        + (this.signature ? 1 << 4 : 0)
        + (this.exceptions ? 1 << 5 : 0)
        + (this.sourceDebugExtension ? 1 << 6 : 0)
        + (this.runtimeVisibleAnnotations ? 1 << 7 : 0)
        + (this.runtimeInvisibleAnnotations ? 1 << 8 : 0)
        + (this.runtimeVisibleParameterAnnotations ? 1 << 9 : 0)
        + (this.runtimeInvisibleParameterAnnotations ? 1 << 10 : 0)
        + (this.runtimeVisibleTypeAnnotations ? 1 << 11 : 0)
        + (this.runtimeInvisibleTypeAnnotations ? 1 << 12 : 0)
        + (this.annotationDefault ? 1 << 13 : 0);
  }
}
