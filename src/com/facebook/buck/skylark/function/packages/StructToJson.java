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

package com.facebook.buck.skylark.function.packages;

import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.Structure;

/** Implementation of {@link StructImpl#toJson()}. */
class StructToJson {

  static void printJson(Object value, StringBuilder sb, String container, String key)
      throws EvalException {
    if (value == Starlark.NONE) {
      sb.append("null");
    } else if (value instanceof Structure) {
      sb.append("{");

      String join = "";
      for (String field : ((Structure) value).getFieldNames()) {
        sb.append(join);
        join = ",";
        appendJSONStringLiteral(sb, field);
        sb.append(':');
        printJson(((Structure) value).getField(field), sb, "struct field", field);
      }
      sb.append("}");
    } else if (value instanceof Dict) {
      sb.append("{");
      String join = "";
      for (Map.Entry<?, ?> entry : ((Dict<?, ?>) value).entrySet()) {
        sb.append(join);
        join = ",";
        if (!(entry.getKey() instanceof String)) {
          throw Starlark.errorf(
              "Keys must be a string but got a %s for %s%s",
              Starlark.type(entry.getKey()), container, key != null ? " '" + key + "'" : "");
        }
        appendJSONStringLiteral(sb, (String) entry.getKey());
        sb.append(':');
        printJson(entry.getValue(), sb, "dict value", String.valueOf(entry.getKey()));
      }
      sb.append("}");
    } else if (value instanceof Sequence) {
      sb.append("[");
      String join = "";
      for (Object item : ((Sequence<?>) value)) {
        sb.append(join);
        join = ",";
        printJson(item, sb, "list element in struct field", key);
      }
      sb.append("]");
    } else if (value instanceof String) {
      appendJSONStringLiteral(sb, (String) value);
    } else if (value instanceof StarlarkInt || value instanceof Boolean) {
      sb.append(value);
    } else {
      throw Starlark.errorf(
          "Invalid text format, expected a struct, a string, a bool, or an int but got a %s for"
              + " %s%s",
          Starlark.type(value), container, key != null ? " '" + key + "'" : "");
    }
  }

  private static void appendJSONStringLiteral(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); ++i) {
      char c = s.charAt(i);
      switch (c) {
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        default:
          out.append(c);
      }
    }
    out.append('"');
  }
}
