/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Map;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Used to generate a function for use within buck.py for the rule described by a
 * {@link Description}.
 */
public class BuckPyFunction {

  /**
   * Properties from the JSON produced by {@code buck.py} that start with this prefix do not
   * correspond to build rule arguments specified by the user. Instead, they contain internal-only
   * metadata, so they should not be printed when the build rule is reproduced.
   */
  public static final String INTERNAL_PROPERTY_NAME_PREFIX = "buck.";

  /**
   * The name of the property in the JSON produced by {@code buck.py} that identifies the type of
   * the build rule being defined.
   */
  public static final String TYPE_PROPERTY_NAME = INTERNAL_PROPERTY_NAME_PREFIX + "type";

  private final ConstructorArgMarshaller argMarshaller;

  public BuckPyFunction(ConstructorArgMarshaller argMarshaller) {
    this.argMarshaller = argMarshaller;
  }

  public String toPythonFunction(BuildRuleType type, Object dto) {

    StringBuilder builder = new StringBuilder();

    SortedSet<ParamInfo<?>> mandatory = Sets.newTreeSet();
    SortedSet<ParamInfo<?>> optional = Sets.newTreeSet();

    for (ParamInfo<?> param : argMarshaller.getAllParamInfo(dto)) {
      if (isSkippable(param)) {
        continue;
      }

      if (param.isOptional()) {
        optional.add(param);
      } else {
        mandatory.add(param);
      }
    }

    @Nullable TargetName defaultName = dto.getClass().getAnnotation(TargetName.class);

    builder.append("@provide_for_build\n")
        .append("def ").append(type.getName()).append("(");

    if (defaultName == null) {
      builder.append("name, ");
    }

    // Construct the args.
    for (ParamInfo<?> param : Iterables.concat(mandatory, optional)) {
      appendPythonParameter(builder, param);
    }
    builder.append("visibility=[], build_env=None):\n")

        // Define the rule.
        .append("  add_rule({\n")
        .append("    '" + TYPE_PROPERTY_NAME + "' : '").append(type.getName()).append("',\n");


    if (defaultName != null) {
      builder.append("    'name' : '").append(defaultName.name()).append("',\n");
    } else {
      builder.append("    'name' : name,\n");
    }

    // Iterate over args.
    for (ParamInfo<?> param : Iterables.concat(mandatory, optional)) {
      builder.append("    '")
          .append(param.getName())
          .append("' : ")
          .append(param.getPythonName())
          .append(",\n");
    }

    builder.append("    'visibility' : visibility,\n");
    builder.append("  }, build_env)\n\n");

    return builder.toString();
  }

  private void appendPythonParameter(StringBuilder builder, ParamInfo<?> param) {
    builder.append(param.getPythonName());
    if (param.isOptional()) {
      builder.append("=").append(getPythonDefault(param));
    }
    builder.append(", ");
  }

  private String getPythonDefault(ParamInfo<?> param) {
    Class<?> resultClass = param.getResultClass();
    if (Map.class.isAssignableFrom(resultClass)) {
      return "{}";
    } else if (Collection.class.isAssignableFrom(resultClass)) {
      return "[]";
    } else if (param.isOptional()) {
      return "None";
    } else if (Boolean.class.equals(resultClass)) {
      return "False";
    } else {
      return "None";
    }
  }

  private boolean isSkippable(ParamInfo<?> param) {
    if ("name".equals(param.getName())) {
      if (!String.class.equals(param.getResultClass())) {
        throw new HumanReadableException("'name' parameter must be a java.lang.String");
      }
      return true;
    }

    if ("visibility".equals(param.getName())) {
      throw new HumanReadableException(
          "'visibility' parameter must be omitted. It will be passed to the rule at run time.");
    }

    return false;
  }
}
