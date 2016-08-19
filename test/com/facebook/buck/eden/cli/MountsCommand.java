/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.eden.cli;

import com.facebook.buck.eden.EdenClient;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.base.Optional;

import java.io.IOException;
import java.util.List;

public class MountsCommand implements Command {
  @Override
  public int run() throws EdenError, IOException, TException {
    Optional<EdenClient> client = EdenClient.newInstance();
    if (!client.isPresent()) {
      System.err.println("Could not connect to Eden");
      return 1;
    }

    List<MountInfo> mountInfos = client.get().getMountInfos();
    System.out.printf("Number of mounts: %d\n", mountInfos.size());
    for (MountInfo info : mountInfos) {
      System.out.println(info.mountPoint);
    }

    return 0;
  }
}
