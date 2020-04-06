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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.dd.plist.NSDictionary;
import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.google.common.collect.Ordering;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The root object representing the project itself. */
public final class PBXProject extends PBXContainer {
  private String name;
  private final PBXGroup mainGroup;
  private final List<PBXTarget> targets;
  private final XCConfigurationList buildConfigurationList;
  private final String compatibilityVersion;

  /** Context information for building the main group of a PBXProject. */
  public static class MainGroupContext {
    AbsPath cellPath;
    RelPath projectPath;

    /**
     * Creates a new main group context.
     *
     * @param cellPath The path to the cell for this project.
     * @param projectPath The path to the project; this should be a subpath of `cellPath`
     */
    public MainGroupContext(AbsPath cellPath, RelPath projectPath) {
      this.cellPath = cellPath;
      this.projectPath = projectPath;
    }
  }

  // Once projectv1 goes away, we can main MainGroupContext non-optional.
  public PBXProject(
      String name,
      Optional<PBXProject.MainGroupContext> mainGroupContext,
      AbstractPBXObjectFactory objectFactory) {
    this.name = name;

    /*
     If we have a context object, set the main group's path relative to the cell root, so that
     the root path of the project is
     A) Relative to the project path
     B) Starts at the cell root.

     e.g. if the cell is //Foo and the project is in /Foo/Bar/Baz, the main group path should be
     ../../ -- this is because all paths after this will simply be the folder name (e.g. Bar) and
     by setting their SourceTree to GROUP, all subsequent paths will be relative to this one.
    */
    String mainGroupPath =
        mainGroupContext
            .map(
                context ->
                    context
                        .cellPath
                        .resolve(context.projectPath)
                        .relativize(context.cellPath)
                        .toString())
            .orElse(null);

    /*
    If we're going to set the mainGroup path to non-null, we need to then tell the project file that
    the main group path is relative to the project (SOURCE_ROOT). Otherwise, use .GROUP to maintain
    backwards compatibility.

    This means that the root of the project will be the cell and all other paths in subdirectories
    will be relative to this main group.
     */
    PBXReference.SourceTree mainGroupSourceTree =
        mainGroupContext
            .map(context -> PBXReference.SourceTree.SOURCE_ROOT)
            .orElse(PBXReference.SourceTree.GROUP);

    this.mainGroup = objectFactory.createPBXGroup("mainGroup", mainGroupPath, mainGroupSourceTree);
    this.targets = new ArrayList<>();
    this.buildConfigurationList = objectFactory.createConfigurationList();
    this.compatibilityVersion = "Xcode 3.2";
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public PBXGroup getMainGroup() {
    return mainGroup;
  }

  public List<PBXTarget> getTargets() {
    return targets;
  }

  public XCConfigurationList getBuildConfigurationList() {
    return buildConfigurationList;
  }

  public String getCompatibilityVersion() {
    return compatibilityVersion;
  }

  @Override
  public String isa() {
    return "PBXProject";
  }

  @Override
  public int stableHash() {
    return name.hashCode();
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    s.addField("mainGroup", mainGroup);

    targets.sort(Ordering.natural().onResultOf(PBXTarget::getName));
    s.addField("targets", targets);
    s.addField("buildConfigurationList", buildConfigurationList);
    s.addField("compatibilityVersion", compatibilityVersion);

    NSDictionary d = new NSDictionary();
    d.put("LastUpgradeCheck", "9999");

    s.addField("attributes", d);
  }
}
