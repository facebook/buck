/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.artifact.Artifact;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.EvalException;

/**
 * Struct containing methods that create actions within the implementation function of a user
 * defined rule
 */
@SkylarkModule(
    name = "actions",
    title = "actions",
    doc = "Struct containing methods to create actions within a rule's implementation method")
public interface SkylarkRuleContextActionsApi {

  @SkylarkCallable(
      name = "declare_file",
      doc = "Declares a file that will be used as the output by subsequent actions",
      useLocation = true,
      parameters = {
        @Param(
            name = "filename",
            doc =
                "The name of the file that will be created. This must be relative and not traverse "
                    + "upward in the filesystem",
            type = String.class,
            named = true)
      })
  Artifact declareFile(String path, Location location) throws EvalException;
}
