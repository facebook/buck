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

package com.facebook.buck.core.starlark.rule.artifact;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.StarlarkValue;

/**
 * Helper struct fields that should be available to users of Artifact inside of user defined rules
 *
 * <p>Note, that unlike https://docs.bazel.build/versions/master/skylark/lib/File.html, we do not
 * presently expose root, dirname, or path to give a fully constructed path from the repository
 * root. Should this be necessary in the future, we may expose those things.
 */
@SkylarkModule(
    name = "Artifact",
    doc = "Represents either a generated file, or a source file",
    title = "Artifact",
    category = SkylarkModuleCategory.BUILTIN)
public interface SkylarkArtifactApi extends StarlarkValue {

  @SkylarkCallable(
      name = "basename",
      doc = "The base name of this artifact. e.g. for an artifact at `foo/bar`, this is `bar`",
      structField = true)
  String getBasename();

  @SkylarkCallable(
      name = "extension",
      doc =
          "The file extension of this artifact. e.g. for an artifact at foo/bar.sh, this is "
              + "`sh`. If no extension is present, an empty string is returned",
      structField = true)
  String getExtension();

  @SkylarkCallable(
      name = "is_source",
      doc = "Whether the artifact represents a source file",
      structField = true)
  boolean isSource();

  @SkylarkCallable(
      name = "owner",
      doc =
          "The `Label` of the rule that originally created this artifact. May also be None in "
              + "the case of source files, or if the artifact has not be used in an action.",
      structField = true)
  Object getOwner();

  @SkylarkCallable(
      name = "short_path",
      doc =
          "The partial path of this artifact.\n"
              + "**This directory component of this path is not guaranteed to follow any specific "
              + "pattern.**\n"
              + "If the artifact is a source artifact, this path is relative to the project root. "
              + "If it is a generated artifact, it is relative to the build artifact root "
              + "directory. e.g. `buck-out/<configuration hash>/gen`. For example, a file "
              + "`baz/qux.cpp` declared by `//foo:bar`, might return `foo/bar__/baz/qux.cpp`.\n"
              + "To get the file's original package, use `Artifact.owner.package`.",
      structField = true)
  String getShortPath();

  @SkylarkCallable(
      name = "as_output",
      doc =
          "Get an instance of this artifact that signals it is intended to be used as an output. "
              + "This is normally only of use with `ctx.actions.run()`, or `ctx.actions.args()`",
      useLocation = true)
  SkylarkOutputArtifactApi asSkylarkOutputArtifact(Location location) throws EvalException;

  @Override
  default boolean isImmutable() {
    // The user-facing attributes of Artifact do not change over the lifetime
    // of the object. An apt comparison is String. It is "immutable", but it has
    // a mutable field that caches the hashcode
    return true;
  }
}
