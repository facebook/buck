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

package com.facebook.buck.step.fs;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;

public class SymlinkTreeStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path root;
  private final ImmutableMap<Path, Path> links;

  public SymlinkTreeStep(ProjectFilesystem filesystem, Path root, ImmutableMap<Path, Path> links) {
    this.filesystem = filesystem;
    this.root = root;
    this.links = links;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "link tree @ " + root.toString();
  }

  @Override
  public String getShortName() {
    return "link_tree";
  }

  @Override
  public int execute(ExecutionContext context) {
    for (ImmutableMap.Entry<Path, Path> ent : links.entrySet()) {
      Path target = filesystem.resolve(ent.getValue());
      Path link = filesystem.resolve(root.resolve(ent.getKey()));
      try {
        filesystem.mkdirs(link.getParent());
        filesystem.createSymLink(link, target, true /* force */);
      } catch (IOException e) {
        String msg = String.format("failed creating linking \"%s\" -> \"%s\"", link, target);
        context.logError(e, msg);
        e.printStackTrace(context.getStdErr());
        return 1;
      }
    }
    return 0;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SymlinkTreeStep)) {
      return false;
    }
    SymlinkTreeStep that = (SymlinkTreeStep) obj;
    return Objects.equal(this.root, that.root) && Objects.equal(this.links, that.links);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(root, links);
  }

}
