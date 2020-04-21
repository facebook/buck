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

package com.facebook.buck.rules.param;

import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import java.util.concurrent.ConcurrentHashMap;

/** Rule param name. */
public class ParamName implements Comparable<ParamName> {

  private final String camelCase;
  private final String snakeCase;

  // We only have a hundred params or so, so it's OK to store them permanently.
  // Even if UDR authors create a million distinct param names, it would still be
  // fine to store them permanently.
  private static final ConcurrentHashMap<String, ParamName> byCamelCase = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, ParamName> bySnakeCase = new ConcurrentHashMap<>();

  private ParamName(String camelCase, String snakeCase) {
    this.camelCase = camelCase;
    this.snakeCase = snakeCase;
  }

  /** Param name by camel case. */
  public String getCamelCase() {
    return camelCase;
  }

  /** Param name by snake case. */
  public String getSnakeCase() {
    return snakeCase;
  }

  private static ParamName put(String camelCase, String snakeCase) {
    ParamName newParamName = new ParamName(camelCase, snakeCase);
    ParamName prevParamName = byCamelCase.putIfAbsent(camelCase, newParamName);
    if (prevParamName != null) {
      // `bySnakeCase` might not yet contain the value, but that's fine,
      // another thread is about to populate the value
      return prevParamName;
    } else {
      prevParamName = bySnakeCase.put(snakeCase, newParamName);

      // The thread which populated `byCamelCase` populates `bySnakeCase`
      // thus it's not possible to see populated value here.
      Preconditions.checkState(prevParamName == null);

      return newParamName;
    }
  }

  private static String validateCase(
      String string, CaseFormat expected, CaseFormat validateAgainst) {
    boolean underscore;
    if (string.startsWith("_")) {
      underscore = true;
      string = string.substring("_".length());
    } else {
      underscore = false;
    }

    Preconditions.checkArgument(!string.isEmpty());

    String converted = expected.to(validateAgainst, string);
    String convertedBack = validateAgainst.to(expected, converted);
    Preconditions.checkArgument(convertedBack.equals(string), "not a %s: %s", expected, string);

    return (underscore ? "_" : "") + converted;
  }

  /**
   * Get a param name object by camel case.
   *
   * <p>This function fails is supplied string is not camel case.
   */
  public static ParamName byCamelCase(String camelCase) {
    // fast path
    ParamName paramName = byCamelCase.get(camelCase);
    if (paramName != null) {
      return paramName;
    }

    String snakeCase = validateCase(camelCase, CaseFormat.LOWER_CAMEL, CaseFormat.LOWER_UNDERSCORE);
    return put(camelCase, snakeCase);
  }

  /**
   * Get a param name object by camel case.
   *
   * <p>This function fails is supplied string is not camel case.
   *
   * <p>This function is slow.
   */
  public static ParamName byUpperCamelCase(String upperCamelCase) {
    return bySnakeCase(
        validateCase(upperCamelCase, CaseFormat.UPPER_CAMEL, CaseFormat.LOWER_UNDERSCORE));
  }

  /**
   * Get a param name object by snake case.
   *
   * <p>This function fails is supplied string is not snake case.
   */
  public static ParamName bySnakeCase(String snakeCase) {
    // fast path
    ParamName paramName = bySnakeCase.get(snakeCase);
    if (paramName != null) {
      return paramName;
    }

    String camelCase = validateCase(snakeCase, CaseFormat.LOWER_UNDERSCORE, CaseFormat.LOWER_CAMEL);

    return put(camelCase, snakeCase);
  }

  // using identity equals and hashCode

  @Override
  public String toString() {
    return snakeCase;
  }

  @Override
  public int compareTo(ParamName that) {
    boolean thisIsName = this.getCamelCase().equals("name");
    boolean thatIsName = that.getCamelCase().equals("name");
    if (thisIsName || thatIsName) {
      // "name" is smaller than all other strings
      return Boolean.compare(!thisIsName, !thatIsName);
    }

    return this.snakeCase.compareTo(that.snakeCase);
  }
}
