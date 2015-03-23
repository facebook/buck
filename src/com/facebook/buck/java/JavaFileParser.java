/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Map;

/**
 * Extracts the set of exported symbols (class and enum names) from a Java code file, using the
 * ASTParser from Eclipse.
 */
public class JavaFileParser {

  private final int jlsLevel;
  private final String javaVersion;

  private static final ImmutableMap<String, String> javaVersionMap =
      ImmutableMap.<String, String>builder()
      .put("1", JavaCore.VERSION_1_1)
      .put("2", JavaCore.VERSION_1_2)
      .put("3", JavaCore.VERSION_1_3)
      .put("4", JavaCore.VERSION_1_4)
      .put("5", JavaCore.VERSION_1_5)
      .put("6", JavaCore.VERSION_1_6)
      .put("7", JavaCore.VERSION_1_7)
      .build();

  private JavaFileParser(int jlsLevel, String javaVersion) {
    this.jlsLevel = jlsLevel;
    this.javaVersion = javaVersion;
  }

  public static JavaFileParser createJavaFileParser(JavacOptions options) {
    String javaVersion = Preconditions.checkNotNull(javaVersionMap.get(options.getSourceLevel()));
    return new JavaFileParser(AST.JLS8, javaVersion);
  }

  public ImmutableSortedSet<String> getExportedSymbolsFromString(String code) throws IOException {
    ASTParser parser = ASTParser.newParser(jlsLevel);
    parser.setSource(code.toCharArray());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);

    @SuppressWarnings("unchecked")
    Map<String, String> options = JavaCore.getOptions();
    JavaCore.setComplianceOptions(javaVersion, options);
    parser.setCompilerOptions(options);

    final CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(/* monitor */ null);

    final ImmutableSortedSet.Builder<String> symbolsBuilder = ImmutableSortedSet.naturalOrder();

    compilationUnit.accept(new ASTVisitor() {
      @Override
      public boolean visit(TypeDeclaration node) {
        // Local classes can be declared inside of methods. Skip over these.
        if (node.getParent() instanceof TypeDeclarationStatement) {
          return true;
        }

        symbolsBuilder.add(getFullyQualifiedTypeName(node));
        return true;
      }

      @Override
      public boolean visit(EnumDeclaration node) {
        symbolsBuilder.add(getFullyQualifiedTypeName(node));
        return true;
      }

      @Override
      public boolean visit(AnnotationTypeDeclaration node) {
        symbolsBuilder.add(getFullyQualifiedTypeName(node));
        return true;
      }
    });

    return symbolsBuilder.build();
  }

  private String getFullyQualifiedTypeName(AbstractTypeDeclaration node) {
    LinkedList<String> nameParts = Lists.newLinkedList();
    nameParts.add(node.getName().toString());
    ASTNode parent = node.getParent();
    while (!(parent instanceof CompilationUnit)) {
      nameParts.addFirst(((AbstractTypeDeclaration) parent).getName().toString());
      parent = parent.getParent();
    }

    // A Java file might not have a package. Hopefully all of ours do though...
    PackageDeclaration packageDecl = ((CompilationUnit) parent).getPackage();
    if (packageDecl != null) {
      nameParts.addFirst(packageDecl.getName().toString());
    }

    return Joiner.on(".").join(nameParts);
  }
}
