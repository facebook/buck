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

package com.facebook.buck.distributed.build_slave.testutil;

import com.facebook.buck.distributed.build_slave.DistBuildChromeTraceRenderer;
import com.google.common.base.Preconditions;
import java.io.File;
import java.util.Random;

/** Use this class to develop trace renderer. */
public class DistBuildChromeTraceRendererExample {

  /** This is main function of the class. */
  public static void main(String[] args) throws Exception {
    String userHome = Preconditions.checkNotNull(System.getProperty("user.home"));
    File file =
        new File(userHome, DistBuildChromeTraceRendererExample.class.getSimpleName() + ".trace");
    DistBuildChromeTraceRenderer.render(DistBuildTraceRandom.random(new Random()), file.toPath());
    System.out.println("random trace written to " + file);
  }
}
