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

package com.facebook.buck.core.io;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class ArchiveMemberPath {
  public abstract Path getArchivePath();

  public abstract Path getMemberPath();

  @Value.Auxiliary
  public boolean isAbsolute() {
    return getArchivePath().isAbsolute();
  }

  @Override
  public String toString() {
    return getArchivePath() + "!/" + getMemberPath();
  }

  public static ArchiveMemberPath of(Path archivePath, Path memberPath) {
    return ImmutableArchiveMemberPath.of(archivePath, memberPath);
  }

  public ArchiveMemberPath withArchivePath(Path archivePath) {
    return of(archivePath, getMemberPath());
  }
}
