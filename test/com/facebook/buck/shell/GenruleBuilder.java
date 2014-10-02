/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import javax.annotation.Nullable;

public class GenruleBuilder extends AbstractBuilder<GenruleDescription.Arg> {
  private GenruleBuilder(BuildTarget target) {
    super(new GenruleDescription(), target);
  }

  public static GenruleBuilder newGenruleBuilder(BuildTarget target) {
    return new GenruleBuilder(target);
  }

  public GenruleBuilder setOut(String out) {
    arg.out = out;
    return this;
  }

  public GenruleBuilder setBash(@Nullable String bash) {
    arg.bash = Optional.fromNullable(bash);
    return this;
  }

  public GenruleBuilder setCmd(@Nullable String cmd) {
    arg.cmd = Optional.fromNullable(cmd);
    return this;
  }

  public GenruleBuilder setCmdExe(@Nullable String cmdExe) {
    arg.cmdExe = Optional.fromNullable(cmdExe);
    return this;
  }

  public GenruleBuilder setSrcs(@Nullable ImmutableList<SourcePath> srcs) {
    arg.srcs = Optional.fromNullable(srcs);
    return this;
  }

  public GenruleBuilder setDeps(@Nullable ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.fromNullable(deps);
    return this;
  }
}
