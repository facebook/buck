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

package com.facebook.buck.skylark.function;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.skylarkbuildapi.StructApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;

/**
 * Defines a set of functions that are available only in Skylark extension files.
 *
 * <p>The implementation is based on Bazel's {@code SkylarkRuleClassFunctions} for compatibility.
 */
public class SkylarkExtensionFunctions {

  @SkylarkSignature(
      name = "struct",
      returnType = Info.class,
      doc =
          "Creates an immutable struct using the keyword arguments as attributes. It is used to "
              + "group multiple values and/or functions together. Example:<br>"
              + "<pre class=\"language-python\">s = struct(x = 2, y = 3)\n"
              + "return s.x + getattr(s, \"y\")  # returns 5</pre>",
      extraKeywords = @Param(name = "kwargs", doc = "the struct attributes."),
      useLocation = true)
  private static final StructProvider struct = StructProvider.STRUCT;

  @SkylarkSignature(
      name = "to_json",
      doc =
          "Creates a JSON string from the struct parameter. This method only works if all "
              + "struct elements (recursively) are strings, ints, booleans, other structs or a "
              + "list of these types. Quotes and new lines in strings are escaped. "
              + "Examples:<br><pre class=language-python>"
              + "struct(key=123).to_json()\n# {\"key\":123}\n\n"
              + "struct(key=True).to_json()\n# {\"key\":true}\n\n"
              + "struct(key=[1, 2, 3]).to_json()\n# {\"key\":[1,2,3]}\n\n"
              + "struct(key='text').to_json()\n# {\"key\":\"text\"}\n\n"
              + "struct(key=struct(inner_key='text')).to_json()\n"
              + "# {\"key\":{\"inner_key\":\"text\"}}\n\n"
              + "struct(key=[struct(inner_key=1), struct(inner_key=2)]).to_json()\n"
              + "# {\"key\":[{\"inner_key\":1},{\"inner_key\":2}]}\n\n"
              + "struct(key=struct(inner_key=struct(inner_inner_key='text'))).to_json()\n"
              + "# {\"key\":{\"inner_key\":{\"inner_inner_key\":\"text\"}}}\n</pre>",
      objectType = StructApi.class,
      returnType = String.class,
      parameters = {@Param(name = "self", type = StructApi.class, doc = "this struct.")},
      useLocation = true)
  private static final BuiltinFunction toJson =
      new BuiltinFunction("to_json") {
        @SuppressWarnings("unused") // it's used through reflection by Skylark runtime
        public String invoke(StructApi self, Location loc) throws EvalException {
          return JsonPrinter.printJson(self, loc);
        }
      };

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(SkylarkExtensionFunctions.class);
  }
}
