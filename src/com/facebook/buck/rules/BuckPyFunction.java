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
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;

import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Optional;

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
  public static final String BUCK_PY_FUNCTION_TEMPLATE = "BuckPyFunction.stg";

  private static final Supplier<STGroup> buckPyFunctionTemplate = Suppliers.memoize(
      () -> new STGroupFile(
          Resources.getResource(BuckPyFunction.class, BUCK_PY_FUNCTION_TEMPLATE),
          "UTF-8",
          '<',
          '>')
  );

  private final ConstructorArgMarshaller argMarshaller;

  public BuckPyFunction(ConstructorArgMarshaller argMarshaller) {
    this.argMarshaller = argMarshaller;
  }

  public String toPythonFunction(BuildRuleType type, Object dto) {
    @Nullable TargetName defaultName = dto.getClass().getAnnotation(TargetName.class);

    ImmutableList.Builder<StParamInfo> mandatory = ImmutableList.builder();
    ImmutableList.Builder<StParamInfo> optional = ImmutableList.builder();
    for (ParamInfo param : ImmutableSortedSet.copyOf(argMarshaller.getAllParamInfo(dto))) {
      if (isSkippable(param)) {
        continue;
      }
      if (param.isOptional()) {
        optional.add(new StParamInfo(param));
      } else {
        mandatory.add(new StParamInfo(param));
      }
    }
    optional.add(StParamInfo.ofOptionalValue("autodeps", "autodeps"));
    optional.add(StParamInfo.ofOptionalValue("visibility", "visibility"));

    STGroup group = buckPyFunctionTemplate.get();
    ST st;
    // STGroup#getInstanceOf may not be thread safe.
    // See discussion in: https://github.com/antlr/stringtemplate4/issues/61
    synchronized (group) {
      st = group.getInstanceOf("buck_py_function");
    }
    st.add("name", type.getName());
    // Mandatory params must come before optional ones.
    st.add(
        "params",
        ImmutableList.builder().addAll(mandatory.build()).addAll(optional.build()).build());
    st.add("typePropName", TYPE_PROPERTY_NAME);
    st.add("defaultName", defaultName == null ? null : defaultName.name());
    StringWriter stringWriter = new StringWriter();
    try {
      st.write(new AutoIndentWriter(stringWriter, "\n"));
    } catch (IOException e) {
      throw new IllegalStateException("ST writer should not throw with StringWriter", e);
    }
    return stringWriter.toString();
  }

  private boolean isSkippable(ParamInfo param) {
    if ("name".equals(param.getName())) {
      if (!String.class.equals(param.getResultClass())) {
        throw new HumanReadableException("'name' parameter must be a java.lang.String");
      }
      return true;
    }

    // Normally, the implicit "autodeps" parameter is all a rule needs, but some Descriptions will
    // also declare it explicitly as a field on its Arg. Normally, this happens when Buck needs
    // access to the value in Java, such as the JavaDepsFinder that powers `buck autodeps`.
    if ("autodeps".equals(param.getName())) {
      if (!Optional.class.equals(param.getResultClass())) {
        throw new HumanReadableException(
            "'autodeps' parameter must be a java.util.Optional<Boolean> but was %s",
            param.getResultClass());
      }
      return true;
    }

    if ("visibility".equals(param.getName())) {
      throw new HumanReadableException(
          "'visibility' parameter must be omitted. It will be passed to the rule at run time.");
    }

    return false;
  }

  @SuppressWarnings("unused")
  private static class StParamInfo {
    public final String name;
    public final String pythonName;
    public final boolean optional;

    public StParamInfo(ParamInfo info) {
      this.name = info.getName();
      this.pythonName = info.getPythonName();
      this.optional = info.isOptional();
    }

    public static StParamInfo ofOptionalValue(String name, String pythonName) {
      return new StParamInfo(name, pythonName, true);
    }

    private StParamInfo(String name, String pythonName, boolean optional) {
      this.name = name;
      this.pythonName = pythonName;
      this.optional = optional;
    }
  }
}
