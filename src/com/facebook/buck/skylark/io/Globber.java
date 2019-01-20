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

package com.facebook.buck.skylark.io;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

/**
 * A contract for globbing functionality that allows resolving file paths based on include patterns
 * (file patterns that should be returned) minus exclude patterns (file patterns that should be
 * excluded from the resulting set).
 */
public interface Globber {
  Set<String> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException, InterruptedException;
}
