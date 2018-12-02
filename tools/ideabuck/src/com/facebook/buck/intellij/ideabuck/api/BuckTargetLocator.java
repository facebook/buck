/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.api;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import java.nio.file.Path;
import java.util.Optional;

/** Resolves {@link BuckTarget} and {@link BuckTargetPattern} to their source files or elements. */
public interface BuckTargetLocator extends ProjectComponent {

  /** Returns a {@link BuckTargetLocator} for the given project. */
  public static BuckTargetLocator getInstance(Project project) {
    return project.getComponent(BuckTargetLocator.class);
  }

  /**
   * Returns a {@link Path} to the Buck file in the given target's package.
   *
   * <p>Note that this does not guarantee that the Buck file exists or that (if the file does exist)
   * the rule definition is actually present in the Buck file.
   */
  Optional<Path> findPathForTarget(BuckTarget buckTarget);

  /**
   * Returns a {@link VirtualFile} for the Buck file in the given target's package.
   *
   * <p>Note that this does not guarantee that the Buck file exists or that (if the file does exist)
   * the rule definition is actually present in the Buck file.
   */
  Optional<VirtualFile> findVirtualFileForTarget(BuckTarget buckTarget);

  /**
   * Returns a {@link Path} to the extension file identified by the given target.
   *
   * <p>Note that this does not guarantee that the extension file actually exists.
   */
  Optional<Path> findPathForExtensionFile(BuckTarget buckTarget);

  /**
   * Returns a {@link VirtualFile} to the extension file identified by the given target.
   *
   * <p>Note that this does not guarantee that the extension file actually exists.
   */
  Optional<VirtualFile> findVirtualFileForExtensionFile(BuckTarget buckTarget);

  /** Returns a {@link Path} to a file or directory that matches the given target pattern. */
  Optional<Path> findPathForTargetPattern(BuckTargetPattern buckTargetPattern);

  /** Returns a {@link VirtualFile} to a file or directory that matches the given target pattern. */
  Optional<VirtualFile> findVirtualFileForTargetPattern(BuckTargetPattern buckTargetPattern);

  /** Returns the element in the PsiTree where the given target is defined, if it can be found. */
  Optional<? extends PsiElement> findElementForTarget(BuckTarget buckTarget);

  /**
   * Returns the Buck file associated with the given {@link VirtualFile}.
   *
   * <p>The Buck file is the nearest file named {@link Cell#getBuildfileName()} located in either
   * the same-directory-as or the nearest-ancestor-of the given {@link VirtualFile}.
   */
  Optional<VirtualFile> findBuckFileForVirtualFile(VirtualFile file);

  /**
   * Returns the Buck file associated with the given {@link Path}.
   *
   * <p>The Buck file is the nearest file named {@link Cell#getBuildfileName()} located in either
   * the same-directory-as or the nearest-ancestor-of the given {@link Path}.
   */
  Optional<Path> findBuckFileForPath(Path path);

  /** Resolves the given target relative to the root of the default cell. */
  BuckTarget resolve(BuckTarget target);

  /** Resolves the given target relative to the given file. */
  Optional<BuckTarget> resolve(Path sourceFile, BuckTarget target);

  /** Resolves the given target relative to the given file. */
  Optional<BuckTarget> resolve(VirtualFile sourceFile, BuckTarget target);

  /** Resolves the given target pattern relative to the root of the default cell. */
  BuckTargetPattern resolve(BuckTargetPattern target);

  /** Resolves the given target pattern relative to the given file. */
  Optional<BuckTargetPattern> resolve(Path sourceFile, BuckTargetPattern pattern);

  /** Resolves the given target pattern relative to the given file. */
  Optional<BuckTargetPattern> resolve(VirtualFile sourceFile, BuckTargetPattern pattern);

  /**
   * Returns a target pattern for a given file.
   *
   * <p>If the file is:
   *
   * <ul>
   *   <li>located in a Buck cell, and the file name is the {@link Cell#getBuildfileName()} for that
   *       cell, returns a target pattern matching all targets in its package.
   *   <li>located in a Buck cell, but is not a build file, returns a target suitable for loading it
   *       as an extension file.
   *   <li>not located in a cell, return {@link Optional#empty()}
   * </ul>
   */
  Optional<BuckTargetPattern> findTargetPatternForVirtualFile(VirtualFile file);

  /**
   * Returns a target pattern for a file at a given path, as per {@link
   * #findTargetPatternForVirtualFile(VirtualFile)}.
   */
  Optional<BuckTargetPattern> findTargetPatternForPath(Path path);
}
