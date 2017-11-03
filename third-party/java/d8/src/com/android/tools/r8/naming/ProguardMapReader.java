// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Range;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.naming.MemberNaming.SingleLineRange;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Parses a Proguard mapping file and produces mappings from obfuscated class names to the original
 * name and from obfuscated member signatures to the original members the obfuscated member
 * was formed of.
 * <p>
 * The expected format is as follows
 * <p>
 * original-type-name ARROW obfuscated-type-name COLON starts a class mapping
 * description and maps original to obfuscated.
 * <p>
 * followed by one or more of
 * <p>
 * signature ARROW name
 * <p>
 * which maps the member with the given signature to the new name. This mapping is not
 * bidirectional as member names are overloaded by signature. To make it bidirectional, we extend
 * the name with the signature of the original member.
 * <p>
 * Due to inlining, we might have the above prefixed with a range (two numbers separated by :).
 * <p>
 * range COLON signature ARROW name
 * <p>
 * This has the same meaning as the above but also encodes the line number range of the member. This
 * may be followed by multiple inline mappings of the form
 * <p>
 * range COLON signature COLON range ARROW name
 * <p>
 * to identify that signature was inlined from the second range to the new line numbers in the first
 * range. This is then followed by information on the call trace to where the member was inlined.
 * These entries have the form
 * <p>
 * range COLON signature COLON number ARROW name
 * <p>
 * and are currently only stored to be able to reproduce them later.
 */
public class ProguardMapReader implements AutoCloseable {

  private final BufferedReader reader;

  @Override
  public void close() throws IOException {
    if (reader != null) {
      reader.close();
    }
  }

  ProguardMapReader(BufferedReader reader) {
    this.reader = reader;
  }

  // Internal parser state
  private int lineNo = 0;
  private int lineOffset = 0;
  private String line;

  private char peek() {
    return peek(0);
  }

  private char peek(int distance) {
    return lineOffset + distance < line.length()
        ? line.charAt(lineOffset + distance)
        : '\n';
  }

  private char next() {
    try {
      return line.charAt(lineOffset++);
    } catch (ArrayIndexOutOfBoundsException e) {
      throw new ParseException("Unexpected end of line");
    }
  }

  private boolean nextLine() throws IOException {
    if (line.length() != lineOffset) {
      throw new ParseException("Expected end of line");
    }
    return skipLine();
  }

  private boolean skipLine() throws IOException {
    lineNo++;
    lineOffset = 0;
    line = reader.readLine();
    return hasLine();
  }

  private boolean hasLine() {
    return line != null;
  }

  // Helpers for common pattern
  private void skipWhitespace() {
    while (Character.isWhitespace(peek())) {
      next();
    }
  }

  private char expect(char c) {
    if (next() != c) {
      throw new ParseException("Expected '" + c + "'");
    }
    return c;
  }

  void parse(ProguardMap.Builder mapBuilder) throws IOException {
    // Read the first line.
    line = reader.readLine();
    parseClassMappings(mapBuilder);
  }

  // Parsing of entries

  private void parseClassMappings(ProguardMap.Builder mapBuilder) throws IOException {
    while (hasLine()) {
      String before = parseType(false);
      skipWhitespace();
      // Workaround for proguard map files that contain entries for package-info.java files.
      if (!acceptArrow()) {
        // If this was a package-info line, we parsed the "package" string.
        if (!before.endsWith("package") || !acceptString("-info")) {
          throw new ParseException("Expected arrow after class name " + before);
        }
        skipLine();
        continue;
      }
      skipWhitespace();
      String after = parseType(false);
      expect(':');
      ClassNaming.Builder currentClassBuilder = mapBuilder.classNamingBuilder(after, before);
      if (nextLine()) {
        parseMemberMappings(currentClassBuilder);
      }
    }
  }

  private void parseMemberMappings(ClassNaming.Builder classNamingBuilder) throws IOException {
    MemberNaming current = null;
    Range previousInlineRange = null;
    Signature previousSignature = null;
    String previousRenamedName = null;
    List<Consumer<MemberNaming>> collectedInfos = new ArrayList<>(10);

    while (Character.isWhitespace(peek())) {
      skipWhitespace();
      Range inlinedLineRange = maybeParseRange();
      if (inlinedLineRange != null) {
        expect(':');
      }
      Signature signature = parseSignature();
      Range originalLineRange;
      if (peek() == ':') {
        // This is an inlining definition
        next();
        originalLineRange = maybeParseRange();
        if (originalLineRange == null) {
          if (!skipLine()) {
            break;
          }
          continue;
        }
      } else {
        originalLineRange = null;
      }
      skipWhitespace();
      skipArrow();
      skipWhitespace();
      String renamedName = parseMethodName();
      // If there is no line number information at the front or if it changes, we have a new
      // segment. Likewise, if the range information on the right hand side has two values, we have
      // a new segment.
      if (inlinedLineRange == null
          || previousInlineRange == null
          || originalLineRange == null
          || !previousInlineRange.equals(inlinedLineRange)
          || !originalLineRange.isSingle()) {
        // We are at a range boundary. Either we parsed something new, or an inline frame is over.
        // We detect this by checking whether the previous signature matches the one of current.
        if (current == null || !previousSignature.equals(current.signature)) {
          if (collectedInfos.size() == 1) {
            current = new MemberNaming(previousSignature, previousRenamedName, previousInlineRange);
            classNamingBuilder.addMemberEntry(current);
          } else {
            if (Log.ENABLED && !collectedInfos.isEmpty()) {
              Log.warn(getClass(),
                  "More than one member entry that forms a new group at %s %s -> %s",
                  previousInlineRange, previousSignature, previousRenamedName);
            }
          }
        } else {
          MemberNaming finalCurrent = current;
          collectedInfos.forEach(info -> info.accept(finalCurrent));
        }
        collectedInfos.clear();
      }
      // Defer the creation of the info until we have the correct member.
      collectedInfos.add((m) -> m.addInliningRange(inlinedLineRange, signature, originalLineRange));
      // We have parsed the whole line, move on.
      previousInlineRange = inlinedLineRange;
      previousSignature = signature;
      previousRenamedName = renamedName;
      if (!nextLine()) {
        break;
      }
    }
    // Process the last round if lines have been read.
    if (current == null || !previousSignature.equals(current.signature)) {
      if (collectedInfos.size() == 1) {
        current = new MemberNaming(previousSignature, previousRenamedName, previousInlineRange);
        classNamingBuilder.addMemberEntry(current);
      }
    } else {
      MemberNaming finalCurrent = current;
      collectedInfos.forEach(info -> info.accept(finalCurrent));
    }
    collectedInfos.clear();
  }

  // Parsing of components

  private void skipIdentifier(boolean allowInit) {
    boolean isInit = false;
    if (allowInit && peek() == '<') {
      // swallow the leading < character
      next();
      isInit = true;
    }
    if (!Character.isJavaIdentifierStart(peek())) {
      throw new ParseException("Identifier expected");
    }
    next();
    while (Character.isJavaIdentifierPart(peek())) {
      next();
    }
    if (isInit) {
      expect('>');
    }
    if (Character.isJavaIdentifierPart(peek())) {
      throw new ParseException("End of identifier expected");
    }
  }

  // Cache for canonicalizing strings.
  // This saves 10% of heap space for large programs.
  final HashMap<String, String> cache = new HashMap<>();

  private String substring(int start) {
    String result = line.substring(start, lineOffset);
    if (cache.containsKey(result)) {
      return cache.get(result);
    }
    cache.put(result, result);
    return result;
  }

  private String parseMethodName() {
    int startPosition = lineOffset;
    skipIdentifier(true);
    while (peek() == '.') {
      next();
      skipIdentifier(true);
    }
    return substring(startPosition);
  }

  private String parseType(boolean allowArray) {
    int startPosition = lineOffset;
    skipIdentifier(false);
    while (peek() == '.') {
      next();
      skipIdentifier(false);
    }
    if (allowArray) {
      while (peek() == '[') {
        next();
        expect(']');
      }
    }
    return substring(startPosition);
  }

  private Signature parseSignature() {
    String type = parseType(true);
    expect(' ');
    String name = parseMethodName();
    Signature signature;
    if (peek() == '(') {
      next();
      String[] arguments;
      if (peek() == ')') {
        arguments = new String[0];
      } else {
        List<String> items = new LinkedList<>();
        items.add(parseType(true));
        while (peek() != ')') {
          expect(',');
          items.add(parseType(true));
        }
        arguments = items.toArray(new String[items.size()]);
      }
      expect(')');
      signature = new MethodSignature(name, type, arguments);
    } else {
      signature = new FieldSignature(name, type);
    }
    return signature;
  }

  private void skipArrow() {
    expect('-');
    expect('>');
  }

  private boolean acceptArrow() {
    if (peek() == '-' && peek(1) == '>') {
      next();
      next();
      return true;
    }
    return false;
  }

  private boolean acceptString(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (peek(i) != s.charAt(i)) {
        return false;
      }
    }
    for (int i = 0; i < s.length(); i++) {
      next();
    }
    return true;
  }

  private Range maybeParseRange() {
    if (!Character.isDigit(peek())) {
      return null;
    }
    int from = parseNumber();
    if (peek() != ':') {
      return new SingleLineRange(from);
    }
    expect(':');
    int to = parseNumber();
    return new Range(from, to);
  }

  private int parseNumber() {
    int result = 0;
    if (!Character.isDigit(peek())) {
      throw new ParseException("Number expected");
    }
    do {
      result *= 10;
      result += Character.getNumericValue(next());
    } while (Character.isDigit(peek()));
    return result;
  }

  private class ParseException extends RuntimeException {

    private final int lineNo;
    private final int lineOffset;
    private final String msg;

    ParseException(String msg) {
      lineNo = ProguardMapReader.this.lineNo;
      lineOffset = ProguardMapReader.this.lineOffset;
      this.msg = msg;
    }

    @Override
    public String toString() {
      return "Parse error [" + lineNo + ":" + lineOffset + "] " + msg;
    }
  }
}
