// Copyright (c) 2017, the Rex project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import java.util.Map;
import java.util.TreeMap;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


/**
 * Abstraction for hidden dex marker intended for the main dex file.
 */
public class Marker {

  public enum Tool {D8, R8}

  private static final String kPrefix = "~~";
  private static final String kD8prefix = kPrefix + Tool.D8 + "{";
  private static final String kR8prefix = kPrefix + Tool.R8 + "{";

  private final TreeMap<String, Object> content;
  private final Tool tool;

  public Marker(Tool tool) {
    this.tool = tool;
    this.content = new TreeMap<>();
  }

  private Marker(Tool tool, JSONObject object) {
    this.tool = tool;
    content = new TreeMap<>();
    // This loop is necessary to make the type checker to shut up.
    for (Object e : object.entrySet()) {
      Map.Entry<?,?> entry = (Map.Entry<?,?>) e;
      content.put(String.valueOf(entry.getKey()), entry.getValue());
    }
  }

  public Tool getTool() {
    return tool;
  }

  public boolean isD8() {
    return tool == Tool.D8;
  }

  public boolean isR8() {
    return tool == Tool.R8;
  }

  public Marker put(String key, int value) {
    // value is converted to Long ensuring equals works with the parsed json string.
    return internalPut(key, Long.valueOf(value));
  }

  public Marker put(String key, String value) {
    return internalPut(key, value);
  }

  private Marker internalPut(String key, Object value) {
    assert (key != null) && (value != null);
    assert !content.containsKey(key);
    content.put(key, value);
    return this;
  }

  @Override
  public String toString() {
    // The JSONObject does not support a predictable sorted serialization of the object.
    // Therefore, a TreeMap is used and iteration is over the keySet.
    StringBuffer sb = new StringBuffer(kPrefix + tool);
    boolean first = true;
    sb.append('{');
    for (String key : content.keySet()) {
      if (first) {
        first = false;
      } else {
        sb.append(',');
      }
      sb.append(JSONObject.toString(key, content.get(key)));
    }
    sb.append('}');
    return sb.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Marker) {
      Marker other = (Marker) obj;
      return (tool == other.tool) && content.equals(other.content);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return tool.hashCode() + 3 * content.hashCode();
  }

  // Try to parse str as a marker.
  // Returns null if parsing fails.
  public static Marker parse(String str) {
    if (str.startsWith(kD8prefix)) {
      return internalParse(Tool.D8, str.substring(kD8prefix.length() - 1));
    }
    if (str.startsWith(kR8prefix)) {
      return internalParse(Tool.R8, str.substring(kR8prefix.length() - 1));
    }
    return null;
  }

  private static Marker internalParse(Tool tool, String str) {
    try {
      Object result =  new JSONParser().parse(str);
      if (result instanceof JSONObject) {
        return new Marker(tool, (JSONObject) result);
      }
    } catch (ParseException e) {
      // Fall through.
    }
    return null;
  }
}
