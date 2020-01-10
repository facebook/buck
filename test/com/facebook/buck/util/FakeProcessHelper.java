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

package com.facebook.buck.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** A fake for {@link ProcessHelper}. */
public class FakeProcessHelper extends ProcessHelper {

  private long currentPid = 0;
  private final Map<Long, ProcessResourceConsumption> resMap = new ConcurrentHashMap<>();

  public FakeProcessHelper() {}

  public void setCurrentPid(long pid) {
    currentPid = pid;
  }

  @Nullable
  @Override
  public Long getPid() {
    return currentPid;
  }

  public void setProcessResourceConsumption(long pid, ProcessResourceConsumption res) {
    resMap.put(pid, res);
  }

  @Nullable
  @Override
  public ProcessResourceConsumption getProcessResourceConsumption(long pid) {
    return resMap.get(pid);
  }
}
