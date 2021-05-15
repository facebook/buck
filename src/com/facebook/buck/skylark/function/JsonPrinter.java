/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.skylark.function;

import com.google.protobuf.TextFormat;
import java.util.List;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.Structure;
import net.starlark.java.syntax.Location;

/**
 * Prints JSON representation of Skylark instances, which includes all primitive times and structs,
 * as long as they contain only instances of the previously mentioned types.
 *
 * <p>The implementation is based on {@code
 * com.google.devtools.build.lib.analysis.skylark.SkylarkRuleClassFunctions} to make them
 * compatible.
 */
class JsonPrinter {

  static String printJson(Object value, Location loc) throws EvalException {
    StringBuilder sb = new StringBuilder();
    printJson(value, sb, loc, "struct field", null);
    return sb.toString();
  }

  private static void printJson(
      Object value, StringBuilder sb, Location loc, String container, String key)
      throws EvalException {
    if (value == Starlark.NONE) {
      sb.append("null");
    } else if (value instanceof Structure) {
      printJson((Structure) value, sb, loc);
    } else if (value instanceof Sequence) {
      printJson(((Sequence<?>) value).asList(), sb, loc, key);
    } else if (value instanceof List) {
      printJson((List<?>) value, sb, loc, key);
    } else if (value instanceof String) {
      printJson((String) value, sb);
    } else if (value instanceof Integer
        || value instanceof StarlarkInt
        || value instanceof Boolean) {
      sb.append(value);
    } else {
      String errorMessage =
          "Invalid text format, expected a struct, a string, a bool, or an int "
              + "but got a "
              + value.getClass().getName()
              + " for "
              + container;
      if (key != null) {
        errorMessage += " '" + key + "'";
      }
      throw new EvalException(loc, errorMessage);
    }
  }

  private static <T> void printJson(List<T> value, StringBuilder sb, Location loc, String key)
      throws EvalException {
    sb.append('[');
    String join = "";
    for (T item : value) {
      sb.append(join);
      join = ",";
      printJson(item, sb, loc, "list element in struct field", key);
    }
    sb.append(']');
  }

  private static void printJson(String value, StringBuilder sb) {
    sb.append('"');
    sb.append(jsonEscapeString(value));
    sb.append('"');
  }

  private static void printJson(Structure value, StringBuilder sb, Location loc)
      throws EvalException {
    sb.append('{');
    String join = "";
    for (String field : value.getFieldNames()) {
      sb.append(join);
      join = ",";
      sb.append('"');
      sb.append(field);
      sb.append("\":");
      printJson(value.getField(field), sb, loc, "struct field", field);
    }
    sb.append('}');
  }

  private static String jsonEscapeString(String string) {
    return escapeDoubleQuotesAndBackslashesAndNewlines(string)
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /**
   * Escapes the given string for use in a JSON string.
   *
   * <p>This escapes double quotes, backslashes, and newlines.
   */
  private static String escapeDoubleQuotesAndBackslashesAndNewlines(String string) {
    return TextFormat.escapeDoubleQuotesAndBackslashes(string).replace("\n", "\\n");
  }
}
