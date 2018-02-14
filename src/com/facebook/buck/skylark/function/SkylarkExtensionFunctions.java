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

import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;

/** Defines a set of functions that are available only in Skylark extension files. */
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
    useLocation = true
  )
  public static final Provider struct = NativeProvider.STRUCT;

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(SkylarkExtensionFunctions.class);
  }
}
