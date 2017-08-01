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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.util.TestProcessExecutorFactory;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class FakeHgCommandlineInterface extends HgCmdLineInterface {
  @Nullable private final Path fakePath;
  private final String fakeRevisionId;

  public FakeHgCommandlineInterface(Path fakePath, String fakeRevisionId) {
    super(new TestProcessExecutorFactory(), Paths.get(""), "", ImmutableMap.of());
    this.fakePath = fakePath;
    this.fakeRevisionId = fakeRevisionId;
  }

  @Override
  @Nullable
  public Path getHgRoot() {
    return fakePath;
  }

  @Override
  public String currentRevisionId() {
    return fakeRevisionId;
  }
}
