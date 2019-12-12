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

package com.facebook.buck.util.shutdown;

public class NonReentrantSystemExitDemo {

  private static NonReentrantSystemExit nonReentrantSystemExit = new NonReentrantSystemExit();

  public static void main(String[] args) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                throw new RuntimeException("haha!");
              }
            });
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          // Uncommenting the next line will cause the test to hang.
          // System.exit(1);
          nonReentrantSystemExit.shutdownSoon(0);
        });
    System.exit(0);
  }

  private NonReentrantSystemExitDemo() {
    // Utility class.
  }
}
