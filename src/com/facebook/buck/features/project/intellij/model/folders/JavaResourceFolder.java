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

package com.facebook.buck.features.project.intellij.model.folders;

import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class JavaResourceFolder extends ResourceFolder {

  public static final ResourceFolderFactory FACTORY = JavaResourceFolder::new;

  public JavaResourceFolder(
      Path path, @Nullable Path resourcesRoot, ImmutableSortedSet<Path> inputs) {
    super(path, resourcesRoot, inputs);
  }

  @Override
  public IjResourceFolderType getResourceFolderType() {
    return IjResourceFolderType.JAVA_RESOURCE;
  }

  @Override
  protected IJFolderFactory getFactory() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ResourceFolderFactory getResourceFactory() {
    return FACTORY;
  }
}
