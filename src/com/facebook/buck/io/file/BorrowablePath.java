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

package com.facebook.buck.io.file;

import java.nio.file.Path;

/**
 * Instance holds a path and instruction on how you can treat the contents referred by the path. It
 * does not force the policy by any means.
 */
public class BorrowablePath {
  public enum Behaviour {
    /** You should NOT borrow the path. It may be used in other places. */
    NOT_BORROWABLE,

    /** You can borrow the path's contents. */
    BORROWABLE,
  }

  private final Behaviour behaviour;
  private final Path path;

  public static BorrowablePath borrowablePath(Path path) {
    return new BorrowablePath(path, Behaviour.BORROWABLE);
  }

  public static BorrowablePath notBorrowablePath(Path path) {
    return new BorrowablePath(path, Behaviour.NOT_BORROWABLE);
  }

  private BorrowablePath(Path path, Behaviour behaviour) {
    this.path = path;
    this.behaviour = behaviour;
  }

  /**
   * @return false if you can NOT borrow (move without copying) the contents of the path; Otherwise
   *     returns true, believing that you know better what you are doing.
   */
  public boolean canBorrow() {
    return behaviour == Behaviour.BORROWABLE;
  }

  /** @return referenced path. */
  public Path getPath() {
    return path;
  }
}
