/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.skylark.function;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.ProviderApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkGlobalLibrary;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;

/**
 * Interface for a global Skylark library containing rule-related helper and registration functions.
 */
@SkylarkGlobalLibrary
public interface SkylarkRuleFunctionsApi {

  @SkylarkCallable(
      name = "provider",
      doc =
          "Creates a declared provider 'constructor'. The return value of this "
              + "function can be used to create \"struct-like\" values. Example:<br>"
              + "<pre class=\"language-python\">data = provider()\n"
              + "d = data(x = 2, y = 3)\n"
              + "print(d.x + d.y) # prints 5</pre>",
      parameters = {
        @Param(
            name = "doc",
            type = String.class,
            legacyNamed = true,
            defaultValue = "''",
            doc =
                "A description of the provider that can be extracted by documentation generating tools."),
        @Param(
            name = "fields",
            doc =
                "If specified, restricts the set of allowed fields. <br>"
                    + "Possible values are:"
                    + "<ul>"
                    + "  <li> list of fields:<br>"
                    + "       <pre class=\"language-python\">provider(fields = ['a', 'b'])</pre><p>"
                    + "  <li> dictionary field name -> documentation:<br>"
                    + "       <pre class=\"language-python\">provider(\n"
                    + "       fields = { 'a' : 'Documentation for a', 'b' : 'Documentation for b' })</pre>"
                    + "</ul>"
                    + "All fields are optional.",
            allowedTypes = {
              @ParamType(type = SkylarkList.class, generic1 = String.class),
              @ParamType(type = SkylarkDict.class)
            },
            noneable = true,
            named = true,
            positional = false,
            defaultValue = "None")
      },
      useLocation = true)
  ProviderApi provider(String doc, Object fields, Location location) throws EvalException;
}
