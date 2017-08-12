// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.naming.signature;

import java.lang.reflect.GenericSignatureFormatError;

/**
 * Implements a parser for the generics signature attribute as defined by JVMS 7 $ 4.3.4.
 * Uses a top-down, recursive descent parsing approach for the following grammar:
 * <pre>
 * ClassSignature ::=
 *     OptFormalTypeParams SuperclassSignature {SuperinterfaceSignature}.
 * SuperclassSignature ::= ClassTypeSignature.
 * SuperinterfaceSignature ::= ClassTypeSignature.
 *
 * OptFormalTypeParams ::=
 *     ["<" FormalTypeParameter {FormalTypeParameter} ">"].
 *
 * FormalTypeParameter ::= Ident ClassBound {InterfaceBound}.
 * ClassBound ::= ":" [FieldTypeSignature].
 * InterfaceBound ::= ":" FieldTypeSignature.
 *
 * FieldTypeSignature ::=
 *     ClassTypeSignature | ArrayTypeSignature | TypeVariableSignature.
 * ArrayTypeSignature ::= "[" TypSignature.
 *
 * ClassTypeSignature ::=
 *     "L" {Ident "/"} Ident OptTypeArguments {"." Ident OptTypeArguments} ";".
 *
 * OptTypeArguments ::= "<" TypeArgument {TypeArgument} ">".
 *
 * TypeArgument ::= ([WildcardIndicator] FieldTypeSignature) | "*".
 * WildcardIndicator ::= "+" | "-".
 *
 * TypeVariableSignature ::= "T" Ident ";".
 *
 * TypSignature ::= FieldTypeSignature | BaseType.
 * BaseType ::= "B" | "C" | "D" | "F" | "I" | "J" | "S" | "Z".
 *
 * MethodTypeSignature ::=
 *     OptFormalTypeParams "(" {TypeSignature} ")" ReturnType {ThrowsSignature}.
 * ThrowsSignature ::= ("^" ClassTypeSignature) | ("^" TypeVariableSignature).
 *
 * ReturnType ::= TypSignature | VoidDescriptor.
 * VoidDescriptor ::= "V".
 * </pre>
 */
public class GenericSignatureParser<T> {

  private final GenericSignatureAction<T> actions;

  /*
   * Parser:
   */
  private char symbol; // 0: eof; else valid term symbol or first char of identifier.

  private String identifier;

  /*
   * Scanner:
   * eof is private to the scan methods
   * and it's set only when a scan is issued at the end of the buffer.
   */
  private boolean eof;

  private char[] buffer;

  private int pos;

  public GenericSignatureParser(GenericSignatureAction<T> actions) {
    this.actions = actions;
  }

  public void parseClassSignature(String signature) {
    actions.start();
    setInput(signature);
    parseClassSignature();
    actions.stop();
  }

  public void parseMethodSignature(String signature) {
    actions.start();
    setInput(signature);
    parseMethodTypeSignature();
    actions.stop();
  }

  public void parseFieldSignature(String signature) {
    actions.start();
    setInput(signature);
    parseFieldTypeSignature();
    actions.stop();
  }

  private void setInput(String input) {
    this.buffer = input.toCharArray();
    this.eof = false;
    pos = 0;
    symbol = 0;
    identifier = null;
    scanSymbol();
  }

  //
  // Parser:
  //

  void parseClassSignature() {
    // ClassSignature ::= OptFormalTypeParameters SuperclassSignature {SuperinterfaceSignature}.

    parseOptFormalTypeParameters();

    // SuperclassSignature ::= ClassTypeSignature.
    parseClassTypeSignature();

    while (symbol > 0) {
      // SuperinterfaceSignature ::= ClassTypeSignature.
      parseClassTypeSignature();
    }
  }

  void parseOptFormalTypeParameters() {
    // OptFormalTypeParameters ::= ["<" FormalTypeParameter {FormalTypeParameter} ">"].

    if (symbol == '<') {
      actions.parsedSymbol(symbol);
      scanSymbol();

      updateFormalTypeParameter();

      while ((symbol != '>') && (symbol > 0)) {
        updateFormalTypeParameter();
      }

      actions.parsedSymbol(symbol);
      expect('>');
    }
  }

  void updateFormalTypeParameter() {
    // FormalTypeParameter ::= Ident ClassBound {InterfaceBound}.
    scanIdentifier();
    assert identifier != null;
    actions.parsedIdentifier(identifier);

    // ClassBound ::= ":" [FieldTypeSignature].
    actions.parsedSymbol(symbol);
    expect(':');

    if (symbol == 'L' || symbol == '[' || symbol == 'T') {
      parseFieldTypeSignature();
    }

    while (symbol == ':') {
      // InterfaceBound ::= ":" FieldTypeSignature.
      actions.parsedSymbol(symbol);
      scanSymbol();
      parseFieldTypeSignature();
    }
  }

  private void parseFieldTypeSignature() {
    // FieldTypeSignature ::= ClassTypeSignature | ArrayTypeSignature | TypeVariableSignature.
    switch (symbol) {
      case 'L':
        parseClassTypeSignature();
        break;
      case '[':
        // ArrayTypeSignature ::= "[" TypSignature.
        actions.parsedSymbol(symbol);
        scanSymbol();
        updateTypeSignature();
        break;
      case 'T':
        updateTypeVariableSignature();
        break;
      default:
        throw new GenericSignatureFormatError();
    }
  }

  private void parseClassTypeSignature() {
    // ClassTypeSignature ::= "L" {Ident "/"} Ident OptTypeArguments {"." Ident OptTypeArguments}
    //  ";".
    actions.parsedSymbol(symbol);
    expect('L');

    StringBuilder qualIdent = new StringBuilder();
    scanIdentifier();
    assert identifier != null;
    while (symbol == '/') {
      qualIdent.append(identifier).append(symbol);
      scanSymbol();
      scanIdentifier();
      assert identifier != null;
    }

    qualIdent.append(this.identifier);
    T parsedEnclosingType = actions.parsedTypeName(qualIdent.toString());

    updateOptTypeArguments();

    while (symbol == '.') {
      // Deal with Member Classes:
      actions.parsedSymbol(symbol);
      scanSymbol();
      scanIdentifier();
      assert identifier != null;
      parsedEnclosingType = actions.parsedInnerTypeName(parsedEnclosingType, identifier);
      updateOptTypeArguments();
    }

    actions.parsedSymbol(symbol);
    expect(';');
  }

  private void updateOptTypeArguments() {
    // OptTypeArguments ::= "<" TypeArgument {TypeArgument} ">".
    if (symbol == '<') {
      actions.parsedSymbol(symbol);
      scanSymbol();

      updateTypeArgument();
      while ((symbol != '>') && (symbol > 0)) {
        updateTypeArgument();
      }

      actions.parsedSymbol(symbol);
      expect('>');
    }
  }

  private void updateTypeArgument() {
    // TypeArgument ::= (["+" | "-"] FieldTypeSignature) | "*".
    if (symbol == '*') {
      actions.parsedSymbol(symbol);
      scanSymbol();
    } else if (symbol == '+') {
      actions.parsedSymbol(symbol);
      scanSymbol();
      parseFieldTypeSignature();
    } else if (symbol == '-') {
      actions.parsedSymbol(symbol);
      scanSymbol();
      parseFieldTypeSignature();
    } else {
      parseFieldTypeSignature();
    }
  }

 private  void updateTypeVariableSignature() {
    // TypeVariableSignature ::= "T" Ident ";".
    actions.parsedSymbol(symbol);
    expect('T');

    scanIdentifier();
    assert identifier != null;
    actions.parsedIdentifier(identifier);

    actions.parsedSymbol(symbol);
    expect(';');
  }

  private void updateTypeSignature() {
    switch (symbol) {
      case 'B':
      case 'C':
      case 'D':
      case 'F':
      case 'I':
      case 'J':
      case 'S':
      case 'Z':
        actions.parsedSymbol(symbol);
        scanSymbol();
        break;
      default:
        // Not an elementary type, but a FieldTypeSignature.
        parseFieldTypeSignature();
    }
  }

  private void parseMethodTypeSignature() {
    // MethodTypeSignature ::= [FormalTypeParameters] "(" {TypeSignature} ")" ReturnType
    //  {ThrowsSignature}.
    parseOptFormalTypeParameters();

    actions.parsedSymbol(symbol);
    expect('(');

    while (symbol != ')' && (symbol > 0)) {
      updateTypeSignature();
    }

    actions.parsedSymbol(symbol);
    expect(')');

    updateReturnType();

    if (symbol == '^') {
      do {
        actions.parsedSymbol(symbol);
        scanSymbol();

        // ThrowsSignature ::= ("^" ClassTypeSignature) | ("^" TypeVariableSignature).
        if (symbol == 'T') {
          updateTypeVariableSignature();
        } else {
          parseClassTypeSignature();
        }
      } while (symbol == '^');
    }
  }

  private void updateReturnType() {
    // ReturnType ::= TypeSignature | "V".
    if (symbol != 'V') {
      updateTypeSignature();
    } else {
      actions.parsedSymbol(symbol);
      scanSymbol();
    }
  }


  //
  // Scanner:
  //

  private void scanSymbol() {
    if (!eof) {
      assert buffer != null;
      if (pos < buffer.length) {
        symbol = buffer[pos];
        pos++;
      } else {
        symbol = 0;
        eof = true;
      }
    } else {
      throw new GenericSignatureFormatError();
    }
  }

  private void expect(char c) {
    if (symbol == c) {
      scanSymbol();
    } else {
      throw new GenericSignatureFormatError();
    }
  }

 private  boolean isStopSymbol(char ch) {
    switch (ch) {
      case ':':
      case '/':
      case ';':
      case '<':
      case '.':
        return true;
      default:
        return false;
    }
  }

  // PRE: symbol is the first char of the identifier.
  // POST: symbol = the next symbol AFTER the identifier.
  private void scanIdentifier() {
    if (!eof) {
      StringBuilder identBuf = new StringBuilder(32);
      if (!isStopSymbol(symbol)) {
        identBuf.append(symbol);

        // FINDBUGS
        char[] bufferLocal = buffer;
        assert bufferLocal != null;
        do {
          char ch = bufferLocal[pos];
          if (((ch >= 'a') && (ch <= 'z')) || ((ch >= 'A') && (ch <= 'Z'))
              || !isStopSymbol(ch)) {
            identBuf.append(bufferLocal[pos]);
            pos++;
          } else {
            identifier = identBuf.toString();
            scanSymbol();
            return;
          }
        } while (pos != bufferLocal.length);
        identifier = identBuf.toString();
        symbol = 0;
        eof = true;
      } else {
        // Ident starts with incorrect char.
        symbol = 0;
        eof = true;
        throw new GenericSignatureFormatError();
      }
    } else {
      throw new GenericSignatureFormatError();
    }
  }
}
