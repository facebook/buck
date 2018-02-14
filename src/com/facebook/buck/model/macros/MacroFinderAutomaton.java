/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.model.macros;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.UnmodifiableIterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * A push-down automaton that searches for occurrences of a macro in linear time with respect to the
 * search string. The automaton keeps track of 5 pieces of state: 1) The current automaton state 2)
 * The position in the input string 3) The current nested depth of parentheses 4) The type of quote
 * (if any) which bounds the current sequence 5) The return state to go back to after an escape
 * sequence ends
 *
 * <p>The automaton accumulates intermediate values in the string builder and the match result
 * builder. The <code>find()</code> method will advance the automaton along the input string until
 * it finds a macro, and returns a match, or until it reaches the end of the string and returns
 * null.
 *
 * <p>Examples of valid matching patterns: $(macro) $(macro argument) $(macro nested
 * parens(argument)) $(macro 'ignored paren )')
 *
 * <p>If the macro is preceeded by a '\' the match result will be marked as escaped, and the capture
 * group will include the escaping backslash. Example: \$(macro)
 *
 * <p>Here are the state transitions in dot format (with glossing over of saved state):
 *
 * <pre>
 *   digraph G {
 *     "SEARCHING" -> "FOUND_DOLLAR"  [label="'$'"];
 *     "FOUND_DOLLAR" -> "SEARCHING"  [label="*"];
 *     "FOUND_DOLLAR" -> "READING_MACRO_NAME"  [label="'('"];
 *     "READING_MACRO_NAME" -> "FOUND_MACRO"  [label="')'"];
 *     "READING_MACRO_NAME" -> "READING_ARGS"  [label="\\s"];
 *     "READING_MACRO_NAME" -> "READING_MACRO_NAME"  [label="\\w"];
 *     "READING_ARGS" -> "FOUND_MACRO"  [label="Balanced ')'"];
 *     "READING_ARGS" -> "READING_QUOTED_ARGS"  [label="'|\""];
 *     "READING_QUOTED_ARGS" -> "READING_ARGS"  [label="Matching '|\""];
 *     "READING_QUOTED_ARGS" -> "READING_QUOTED_ARGS"  [label="[^'\"]"];
 *     "READING_ARGS" -> "READING_ARGS"  [label="[^'\")]"];
 *     "SEARCHING" -> "IN_ESCAPE_SEQUENCE"  [label="\\"];
 *     "IN_ESCAPE_SEQUENCE" -> "FOUND_DOLLAR"  [label="$"];
 *     "READING_ARGS" -> "IN_ESCAPE_ARG_SEQUENCE"  [label="\\"];
 *     "READING_QUOTED_ARGS" -> "IN_ESCAPE_ARG_SEQUENCE"  [label="\\"];
 *   }
 * </pre>
 *
 * Not shown: transitions back from "IN_ESCAPE_SEQUENCE" and "IN_ESCAPE_ARG_SEQUENCE", as they
 * return to the previous state
 *
 * <p>The EBNF grammar for a macro definition is as follows:
 *
 * <pre>
 *   macro = "$(", macro_name, whitespace, [arg_list], ")";
 *   macro_name = {all_ascii_chars - whitespace - parens};
 *   whitespace = "\t" | "\n" | " " | "\r";
 *   parens = "(" | ")";
 *   arg_list = arg | arg, whitespace, arg_list;
 *   arg = {all_ascii_chars - whitespace - parens}
 *         | "(", arg, ")"
 *         | "\"", [{-"\""}], "\""
 *         | "'", [{-"'"}], "'";
 *
 * </pre>
 *
 * This documentation and grammar are published in the string_parameters_macro.soy documentation.
 */
public class MacroFinderAutomaton extends UnmodifiableIterator<MacroMatchResult> {

  private enum State {
    // Looking for the start of a macro
    SEARCHING,
    // The last character was a '\' so we're looking for an escaped macro
    IN_ESCAPE_SEQUENCE,
    // The last character was a '$' so we're looking for a '('
    FOUND_DOLLAR,
    // We found a '$' followed by a '(' so now we're reading the macro name
    READING_MACRO_NAME,
    // We finished the macro name, now we count matching parens until we run out
    READING_ARGS,
    // We don't want to count parentheses inside of quotes, so we switch to counting quotes
    READING_QUOTED_ARGS,
    // The last character was a '\' so we won't count a ')' or a quote next
    IN_ESCAPE_ARG_SEQUENCE,
    // We have found a macro and it is ready to be built
    FOUND_MACRO,
    // Then transition back to SEARCHING
  }

  private String blob;

  private State state;
  private int index;
  private int parenthesesDepth;
  private char startQuote;
  private State returnState;

  private MacroMatchResult.Builder currentResultBuilder;
  private StringBuilder buffer;

  @Nullable private MacroMatchResult next;

  public MacroFinderAutomaton(String blob) {
    this.blob = blob;
    this.parenthesesDepth = 0;
    this.state = State.SEARCHING;
    this.index = 0;
    this.currentResultBuilder = MacroMatchResult.builder();
    this.buffer = new StringBuilder();
    this.startQuote = '\0';
    this.returnState = this.state;

    // Initialize the iterator
    next = find();
  }

  @Nullable
  private MacroMatchResult find() {
    for (; index < blob.length(); ++index) {
      state = consumeChar(blob.charAt(index), index);
      if (state == State.FOUND_MACRO) {
        state = State.SEARCHING;
        return currentResultBuilder.build();
      }
    }
    return null;
  }

  @Override
  public boolean hasNext() {
    return next != null;
  }

  @Override
  public MacroMatchResult next() {
    MacroMatchResult toReturn = this.next;
    next = find();
    if (toReturn == null) {
      throw new NoSuchElementException("No more macro matches");
    }
    return toReturn;
  }

  private String takeBuffer() {
    String result = buffer.toString();
    buffer.setLength(0);
    return result;
  }

  private State consumeChar(char c, int index) {
    switch (this.state) {
      case SEARCHING:
        switch (c) {
          case '\\':
            returnState = State.SEARCHING;
            return State.IN_ESCAPE_SEQUENCE;
          case '$':
            currentResultBuilder =
                MacroMatchResult.builder().setStartIndex(index).setEscaped(false);
            return State.FOUND_DOLLAR;
          default:
            return State.SEARCHING;
        }
      case IN_ESCAPE_SEQUENCE:
        // We want to capture the escaped macro, but mark it as escaped
        switch (c) {
          case '$':
            currentResultBuilder =
                MacroMatchResult.builder().setStartIndex(index - 1).setEscaped(true);
            return State.FOUND_DOLLAR;
          default:
            return returnState;
        }
      case FOUND_DOLLAR:
        switch (c) {
          case '(':
            parenthesesDepth += 1;
            return State.READING_MACRO_NAME;
          case '\\':
            returnState = State.SEARCHING;
            return State.IN_ESCAPE_SEQUENCE;
          case '$':
            currentResultBuilder =
                MacroMatchResult.builder().setStartIndex(index).setEscaped(false);
            return State.FOUND_DOLLAR;
          default:
            return State.SEARCHING;
        }
      case READING_MACRO_NAME:
        switch (c) {
          case ')':
            parenthesesDepth -= 1;
            currentResultBuilder
                .setMacroInput(ImmutableList.of())
                .setMacroType(takeBuffer())
                .setEndIndex(index + 1);
            return State.FOUND_MACRO;
          case '\t':
          case ' ':
          case '\n':
          case '\r':
            currentResultBuilder.setMacroType(takeBuffer());
            return State.READING_ARGS;
          default:
            buffer.append(c);
            return State.READING_MACRO_NAME;
        }
      case READING_ARGS:
        switch (c) {
          case ' ':
            currentResultBuilder.addMacroInput(takeBuffer());
            return State.READING_ARGS;
          case '\\':
            returnState = State.READING_ARGS;
            return State.IN_ESCAPE_ARG_SEQUENCE;
          case '\'':
          case '"':
            startQuote = c;
            return State.READING_QUOTED_ARGS;
          case '(':
            parenthesesDepth += 1;
            buffer.append(c);
            return State.READING_ARGS;
          case ')':
            parenthesesDepth -= 1;
            if (parenthesesDepth == 0) {
              currentResultBuilder.addMacroInput(takeBuffer()).setEndIndex(index + 1);
              return State.FOUND_MACRO;
            } else {
              buffer.append(c);
            }
            return State.READING_ARGS;
          default:
            buffer.append(c);
            return State.READING_ARGS;
        }
      case READING_QUOTED_ARGS:
        switch (c) {
          case '\\':
            returnState = State.READING_QUOTED_ARGS;
            return State.IN_ESCAPE_ARG_SEQUENCE;
          case '"':
          case '\'':
            if (c == startQuote) {
              startQuote = '\0';
              return State.READING_ARGS;
            } else {
              buffer.append(c);
              return State.READING_QUOTED_ARGS;
            }
          default:
            buffer.append(c);
            return State.READING_QUOTED_ARGS;
        }
      case IN_ESCAPE_ARG_SEQUENCE:
        buffer.append(c);
        return returnState;
      case FOUND_MACRO:
        throw new IllegalStateException(
            "The state must be reset to searching before more input may be consumed");
    }
    throw new IllegalStateException("Unknown state " + state);
  }
}
