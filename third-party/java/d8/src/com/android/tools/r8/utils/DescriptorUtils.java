// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.FileUtils.CLASS_EXTENSION;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.ClassNameMapper;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

public class DescriptorUtils {

  public static final char DESCRIPTOR_PACKAGE_SEPARATOR = '/';
  public static final char JAVA_PACKAGE_SEPARATOR = '.';
  private static final Map<String, String> typeNameToLetterMap =
      ImmutableMap.<String, String>builder()
          .put("void", "V")
          .put("boolean", "Z")
          .put("byte", "B")
          .put("short", "S")
          .put("char", "C")
          .put("int", "I")
          .put("long", "J")
          .put("float", "F")
          .put("double", "D")
          .build();

  private static String internalToDescriptor(String typeName, boolean shorty) {
    String descriptor = typeNameToLetterMap.get(typeName);
    if (descriptor != null) {
      return descriptor;
    }
    // Must be some array or object type.
    if (shorty) {
      return "L";
    }
    if (typeName.endsWith("[]")) {
      return "[" + internalToDescriptor(typeName.substring(0, typeName.length() - 2), shorty);
    }
    // Must be an object type.
    return "L" + typeName.replace(JAVA_PACKAGE_SEPARATOR, DESCRIPTOR_PACKAGE_SEPARATOR) + ";";
  }

  /**
   * Convert a Java type name to a descriptor string.
   *
   * @param typeName the java type name
   * @return the descriptor string
   */
  public static String javaTypeToDescriptor(String typeName) {
    assert typeName.indexOf(DESCRIPTOR_PACKAGE_SEPARATOR) == -1;
    return internalToDescriptor(typeName, false);
  }

  /**
   * Convert a Java type name to a shorty descriptor string.
   *
   * @param typeName the java type name
   * @return the shorty descriptor string
   */
  public static String javaTypeToShorty(String typeName) {
    return internalToDescriptor(typeName, true);
  }

  /**
   * Convert a type descriptor to a Java type name.
   *
   * @param descriptor type descriptor
   * @return Java type name
   */
  public static String descriptorToJavaType(String descriptor) {
    return descriptorToJavaType(descriptor, null);
  }

  /**
   * Convert a type descriptor to a Java type name. Will also deobfuscate class names if a
   * class mapper is provided.
   *
   * @param descriptor type descriptor
   * @param classNameMapper class name mapper for mapping obfuscated class names
   * @return Java type name
   */
  public static String descriptorToJavaType(String descriptor, ClassNameMapper classNameMapper) {
    switch (descriptor.charAt(0)) {
      case 'L':
        assert descriptor.charAt(descriptor.length() - 1) == ';';
        String clazz = descriptor.substring(1, descriptor.length() - 1)
            .replace(DESCRIPTOR_PACKAGE_SEPARATOR, JAVA_PACKAGE_SEPARATOR);
        String originalName =
            classNameMapper == null ? clazz : classNameMapper.deobfuscateClassName(clazz);
        return originalName;
      case '[':
        return descriptorToJavaType(descriptor.substring(1, descriptor.length()), classNameMapper)
            + "[]";
      case 'V':
        return "void";
      case 'Z':
        return "boolean";
      case 'B':
        return "byte";
      case 'S':
        return "short";
      case 'C':
        return "char";
      case 'I':
        return "int";
      case 'J':
        return "long";
      case 'F':
        return "float";
      case 'D':
        return "double";
      default:
        throw new Unreachable("Unknown type " + descriptor);
    }
  }

  /**
   * Get simple class name from its descriptor.
   *
   * @param classDescriptor a class descriptor i.e. "Ljava/lang/Object;"
   * @return class name i.e. "Object"
   */
  public static String getSimpleClassNameFromDescriptor(String classDescriptor) {
    return getSimpleClassNameFromBinaryName(getClassBinaryNameFromDescriptor(classDescriptor));
  }

  /**
   * Get class name from its descriptor.
   *
   * @param classDescriptor a class descriptor i.e. "Ljava/lang/Object;"
   * @return full class name i.e. "java.lang.Object"
   */
  public static String getClassNameFromDescriptor(String classDescriptor) {
    return getClassBinaryNameFromDescriptor(classDescriptor)
        .replace(DESCRIPTOR_PACKAGE_SEPARATOR, JAVA_PACKAGE_SEPARATOR);
  }

  /**
   * Get package java name from a class descriptor.
   *
   * @param descriptor a class descriptor i.e. "Ljava/lang/Object;"
   * @return java package name i.e. "java.lang"
   */
  public static String getPackageNameFromDescriptor(String descriptor) {
    return getPackageNameFromBinaryName(getClassBinaryNameFromDescriptor(descriptor));
  }

  /**
   * Convert class descriptor to a binary name.
   *
   * @param classDescriptor a class descriptor i.e. "Ljava/lang/Object;"
   * @return class binary name i.e. "java/lang/Object"
   */
  public static String getClassBinaryNameFromDescriptor(String classDescriptor) {
    assert isClassDescriptor(classDescriptor) : "Invalid class descriptor "
        + classDescriptor;
    return classDescriptor.substring(1, classDescriptor.length() - 1);
  }

  /**
   * Convert package name to a binary name.
   *
   * @param packageName a package name i.e., "java.lang"
   * @return java package name in a binary name format, i.e., java/lang
   */
  public static String getPackageBinaryNameFromJavaType(String packageName) {
    return packageName.replace(JAVA_PACKAGE_SEPARATOR, DESCRIPTOR_PACKAGE_SEPARATOR);
  }

  /**
   * Convert a class binary name to a descriptor.
   *
   * @param typeBinaryName class binary name i.e. "java/lang/Object"
   * @return a class descriptor i.e. "Ljava/lang/Object;"
   */
  public static String getDescriptorFromClassBinaryName(String typeBinaryName) {
    return ('L' + typeBinaryName + ';');
  }

  /**
   * Get class name from its binary name.
   *
   * @param classBinaryName a class binary name i.e. "java/lang/Object"
   * @return class name i.e. "Object"
   */
  public static String getSimpleClassNameFromBinaryName(String classBinaryName) {
    int simpleNameIndex = classBinaryName.lastIndexOf(DESCRIPTOR_PACKAGE_SEPARATOR);
    return (simpleNameIndex < 0) ? classBinaryName : classBinaryName.substring(simpleNameIndex + 1);
  }

  public static boolean isClassDescriptor(String descriptor) {
    char[] buffer = descriptor.toCharArray();
    int length = buffer.length;
    if (length < 3 || buffer[0] != 'L') {
      return false;
    }

    int pos = 1;
    char ch;
    do {
      // First letter of an Ident (an Ident can't be empty)
      if (pos >= length) {
        return false;
      }

      ch = buffer[pos++];
      if (isInvalidChar(ch) || ch == DESCRIPTOR_PACKAGE_SEPARATOR || ch == ';') {
        return false;
      }

      // Next letters of an Ident
      do {
        if (pos >= length) {
          return false;
        }

        ch = buffer[pos++];
        if (isInvalidChar(ch)) {
          return false;
        }
      } while (ch != DESCRIPTOR_PACKAGE_SEPARATOR && ch != ';');

    } while (ch != ';');

    return pos == length;
  }

  /**
   * Get package java name from a class binary name
   *
   * @param classBinaryName a class binary name i.e. "java/lang/Object"
   * @return java package name i.e. "java.lang"
   */
  public static String getPackageNameFromBinaryName(String classBinaryName) {
    int nameIndex = classBinaryName.lastIndexOf(DESCRIPTOR_PACKAGE_SEPARATOR);
    return (nameIndex < 0) ? "" : classBinaryName.substring(0, nameIndex)
        .replace(DESCRIPTOR_PACKAGE_SEPARATOR, JAVA_PACKAGE_SEPARATOR);
  }

  private static boolean isInvalidChar(char ch) {
    switch (ch) {
      case JAVA_PACKAGE_SEPARATOR:
      case '[':
        return true;
      default:
        return false;
    }
  }

  // Guess class descriptor from location of the class file.
  public static String guessTypeDescriptor(Path name) {
    return guessTypeDescriptor(name.toString());
  }

  // Guess class descriptor from location of the class file.
  public static String guessTypeDescriptor(String name) {
    assert name != null;
    assert name.endsWith(CLASS_EXTENSION) :
        "Name " + name + " must have " + CLASS_EXTENSION + " suffix";
    String fileName =
        File.separatorChar == '/' ? name.toString() :
            name.toString().replace(File.separatorChar, '/');
    String descriptor = fileName.substring(0, fileName.length() - CLASS_EXTENSION.length());
    if (descriptor.contains(".")) {
      throw new CompilationError("Unexpected class file name: " + fileName);
    }
    return 'L' + descriptor + ';';
  }
}
