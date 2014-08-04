/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.InputStream;
import java.io.IOException;

import java.util.HashMap;

/**
 * Parser for xcode project files.
 */
public class ProjectParser {

  private static final Logger LOG = Logger.get(ProjectParser.class);

  // Utility class; do not instantiate.
  private ProjectParser() { }

  /**
   * Given an input stream opened to a project.pbxproj file, extracts
   * all the objects and returns a dictionary of {GID: ObjectNSDictionary} pairs.
   */
  public static NSDictionary extractObjectsFromXcodeProject(InputStream projectInputStream)
      throws IOException {
    NSObject rootObject;
    try {
      rootObject = PropertyListParser.parse(projectInputStream);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      rootObject = null;
    }
    if (!(rootObject instanceof NSDictionary)) {
      throw new HumanReadableException("Malformed Xcode project");
    }
    NSDictionary rootDictionary = (NSDictionary) rootObject;
    NSObject objects = rootDictionary.objectForKey("objects");
    if (!(objects instanceof NSDictionary)) {
      throw new HumanReadableException("Malformed Xcode project");
    }
    return (NSDictionary) objects;
  }

  /**
   * Given a dictionary of {GID: ObjectNSDictionary} pairs and a
   * builder, writes into the builder the {TargetName: TargetGID}
   * pairs for all objects of PBXTarget subtype.
   */
  public static void extractTargetNameToGIDMap(
      NSDictionary objects,
      ImmutableMap.Builder<String, String> targetNamesToGIDs) {
    Optional<ImmutableMap.Builder<String, String>> targetNamesToFileNames = Optional.absent();
    doExtractTargetNameToGIDAndFileNameMaps(objects, targetNamesToGIDs, targetNamesToFileNames);
  }

  /**
   * Given a dictionary of {GID: ObjectNSDictionary} pairs and
   * builders for {TargetName: TargetGID} and {TargetName: TargetFileName} pairs,
   * fills out the builders with the name:gid and name:filename pairs.
   */
  public static void extractTargetNameToGIDAndFileNameMaps(
      NSDictionary objects,
      ImmutableMap.Builder<String, String> targetNamesToGIDs,
      ImmutableMap.Builder<String, String> targetNamesToFileNames) {
    doExtractTargetNameToGIDAndFileNameMaps(
        objects,
        targetNamesToGIDs,
        Optional.of(targetNamesToFileNames));
  }

  private static void doExtractTargetNameToGIDAndFileNameMaps(
      NSDictionary objects,
      ImmutableMap.Builder<String, String> targetNamesToGIDs,
      Optional<ImmutableMap.Builder<String, String>> targetNamesToFileNames) {
    HashMap<String, String> builtProductReferenceGidsToPaths = new HashMap<>();

    for (String gid : objects.allKeys()) {
      NSObject object = objects.objectForKey(gid);
      if (!(object instanceof NSDictionary)) {
        throw new HumanReadableException("Malformed Xcode project (non-dictionary object)");
      }
      NSDictionary objectDict = (NSDictionary) object;
      NSObject isa = objectDict.objectForKey("isa");
      if (!(isa instanceof NSString)) {
        throw new HumanReadableException("Malformed Xcode project (non-string isa)");
      }
      // No need really to cast here just to call toString().
      switch (isa.toString()) {
        case "PBXNativeTarget":
          // Fall through.
        case "PBXAggregateTarget":
        {
          NSObject name = objectDict.objectForKey("name");
          if (!(name instanceof NSString)) {
            throw new HumanReadableException("Malformed Xcode project (non-string name)");
          }
          targetNamesToGIDs.put(name.toString(), gid);
          NSObject productReference = objectDict.objectForKey("productReference");
          if (productReference != null) {
            if (!(productReference instanceof NSString)) {
              throw new HumanReadableException(
                  "Malformed Xcode project (non-string productReference)");
            }
            String targetFileName = builtProductReferenceGidsToPaths.get(
                productReference.toString());
            if (targetFileName == null) {
              LOG.error(
                  "Target %s has no built product reference (looked for gid %s)",
                  name.toString(),
                  gid);
              throw new HumanReadableException(
                  "Malformed Xcode project (PBXFileReference %s missing)",
                  productReference.toString());
            }
            LOG.debug(
                "Mapped target %s to built product filename %s (gid %s)",
                name.toString(),
                targetFileName,
                gid);
            if (targetNamesToFileNames.isPresent()) {
              targetNamesToFileNames.get().put(name.toString(), targetFileName);
            }
          }
          break;
        }
        case "PBXFileReference":
          NSObject sourceTree = objectDict.objectForKey("sourceTree");
          if (!(sourceTree instanceof NSString)) {
            throw new HumanReadableException("Malformed Xcode project (non-string sourceTree)");
          }
          if (sourceTree.toString().equals("BUILT_PRODUCTS_DIR")) {
            NSObject path = objectDict.objectForKey("path");
            if (!(path instanceof NSString)) {
              throw new HumanReadableException("Malformed Xcode project (non-string path)");
            }
            builtProductReferenceGidsToPaths.put(gid, path.toString());
          }
          break;
      }
    }
  }
}
