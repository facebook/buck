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

package com.facebook.buck.features.project.intellij.moduleinfo;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A binary file format that contains the information needed to reconstruct all
 * com.intellij.openapi.module.UnloadedModuleDescription in the project
 */
public class ModuleInfoBinaryIndex {
  public static final String NAME = "module-info.bin";

  public static final int VERSION = 1;

  private final Path path;

  public ModuleInfoBinaryIndex(Path ideaConfigDir) {
    this.path = ideaConfigDir.resolve(NAME);
  }

  /** The path to the binary index file */
  public Path getStoragePath() {
    return path;
  }

  /** Write all moduleInfos into a file */
  public void write(Collection<ModuleInfo> moduleInfos) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream dataOutput = new DataOutputStream(bytes)) {
      dataOutput.writeShort(VERSION);
      Map<String, Integer> stringToId = createStringToIdMap(moduleInfos);
      writeStringToIdMap(dataOutput, stringToId);
      writeModuleInfos(dataOutput, stringToId, moduleInfos);
    }

    try (FileOutputStream fileOutput = new FileOutputStream(path.toFile())) {
      fileOutput.write(bytes.toByteArray());
    }
  }

  /**
   * Merge the moduleInfos with the existing ones in the file and write the result back to the file
   */
  public void update(Collection<ModuleInfo> moduleInfos) throws IOException {
    List<ModuleInfo> oldModuleInfos = read();
    List<ModuleInfo> merged = mergeModuleInfos(oldModuleInfos, moduleInfos);
    write(merged);
  }

  @VisibleForTesting
  static List<ModuleInfo> mergeModuleInfos(
      Collection<ModuleInfo> oldModuleInfos, Collection<ModuleInfo> newModuleInfos) {
    Map<String, ModuleInfo> newModuleInfoMap =
        newModuleInfos.stream()
            .collect(Collectors.toMap(ModuleInfo::getModuleName, Function.identity()));
    List<ModuleInfo> merged = new ArrayList<>();
    for (ModuleInfo oldInfo : oldModuleInfos) {
      ModuleInfo newInfo = newModuleInfoMap.remove(oldInfo.getModuleName());
      // replace the existing entry if they have the same name
      merged.add(newInfo == null ? oldInfo : newInfo);
    }
    merged.addAll(newModuleInfoMap.values());
    return merged;
  }

  private Map<String, Integer> createStringToIdMap(Collection<ModuleInfo> moduleInfos) {
    Map<String, Integer> stringToId = new LinkedHashMap<>();
    Function<String, Integer> mapping = k -> stringToId.size();
    for (ModuleInfo moduleInfo : moduleInfos) {
      stringToId.computeIfAbsent(moduleInfo.getModuleName(), mapping);
      stringToId.computeIfAbsent(moduleInfo.getModuleDir(), mapping);
    }
    return stringToId;
  }

  private void writeStringToIdMap(DataOutputStream dataOutput, Map<String, Integer> stringToId)
      throws IOException {
    dataOutput.writeInt(stringToId.size());
    int count = 0;
    for (Map.Entry<String, Integer> entry : stringToId.entrySet()) {
      Preconditions.checkState(
          entry.getValue() == count, "String index doesn't preserve the order of insertions");
      dataOutput.writeUTF(entry.getKey());
      count++;
    }
  }

  private String[] readIdToStringArray(DataInputStream dataInput) throws IOException {
    int size = dataInput.readInt();
    String[] idToString = new String[size];
    for (int i = 0; i < size; i++) {
      idToString[i] = dataInput.readUTF();
    }
    return idToString;
  }

  private void writeModuleInfos(
      DataOutputStream dataOutput,
      Map<String, Integer> stringToId,
      Collection<ModuleInfo> moduleInfos)
      throws IOException {
    dataOutput.writeInt(moduleInfos.size());
    for (ModuleInfo moduleInfo : moduleInfos) {
      // write module name
      writeStringId(dataOutput, stringToId, moduleInfo.getModuleName(), "Module name");
      // write module directory
      writeStringId(dataOutput, stringToId, moduleInfo.getModuleDir(), "Module directory");
      // write module dependencies
      dataOutput.writeInt(moduleInfo.getModuleDependencies().size());
      for (String depName : moduleInfo.getModuleDependencies()) {
        writeStringId(dataOutput, stringToId, depName, "Module dependency");
      }
      // write content roots
      dataOutput.writeInt(moduleInfo.getContentRootInfos().size());
      for (ContentRootInfo contentRootInfo : moduleInfo.getContentRootInfos()) {
        dataOutput.writeUTF(contentRootInfo.getUrl());
        writeStringList(dataOutput, contentRootInfo.getRelativeSourceFolders());
        writeStringList(dataOutput, contentRootInfo.getRelativeExcludeFolders());
      }
    }
  }

  private static void writeStringId(
      DataOutputStream dataOutput, Map<String, Integer> stringToId, String s, String type)
      throws IOException {
    Integer id = stringToId.get(s);
    Preconditions.checkState(id != null, type + ": " + s + " not in the string to id map");
    dataOutput.writeInt(id);
  }

  private static void writeStringList(DataOutputStream dataOutput, List<String> list)
      throws IOException {
    dataOutput.writeInt(list.size());
    for (String value : list) {
      dataOutput.writeUTF(value);
    }
  }

  /** Wehther the index is valid or not. Currently we only check the existence of the file */
  public boolean isValid() {
    return Files.exists(path);
  }

  /** Read all moduleInfos from the file */
  public List<ModuleInfo> read() throws IOException {
    if (isValid()) {
      ByteArrayInputStream bytes = new ByteArrayInputStream(Files.readAllBytes(path));
      try (DataInputStream dataInput = new DataInputStream(bytes)) {
        int version = dataInput.readShort();
        if (version == 1) {
          return readV1(dataInput);
        }
      }
    }
    return Collections.emptyList();
  }

  private List<ModuleInfo> readV1(DataInputStream dataInput) throws IOException {
    String[] idToString = readIdToStringArray(dataInput);
    return readModuleInfos(dataInput, idToString);
  }

  private List<ModuleInfo> readModuleInfos(DataInputStream dataInput, String[] idToString)
      throws IOException {
    int size = dataInput.readInt();
    List<ModuleInfo> moduleInfos = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      // read module name
      String moduleName = readStringId(dataInput, idToString, "Module name");
      // read module directory
      String moduleDir = readStringId(dataInput, idToString, "Module directory");
      // read module dependencies
      int depSize = dataInput.readInt();
      List<String> moduleDependencies = new ArrayList<>(depSize);
      for (int j = 0; j < depSize; j++) {
        moduleDependencies.add(readStringId(dataInput, idToString, "Module dependency"));
      }
      // read content roots
      int contentRootSize = dataInput.readInt();
      List<ContentRootInfo> contentRootInfos = new ArrayList<>(contentRootSize);
      for (int j = 0; j < contentRootSize; j++) {
        String url = dataInput.readUTF();
        List<String> relativeSourceFolders = readStringList(dataInput, "source folders");
        List<String> relativeExcludeFolders = readStringList(dataInput, "exclude folders");
        contentRootInfos.add(
            ContentRootInfo.of(url, relativeSourceFolders, relativeExcludeFolders));
      }
      moduleInfos.add(ModuleInfo.of(moduleName, moduleDir, moduleDependencies, contentRootInfos));
    }
    return moduleInfos;
  }

  private String readStringId(DataInputStream dataInput, String[] idToString, String type)
      throws IOException {
    int id = dataInput.readInt();
    Preconditions.checkState(
        id >= 0 && id < idToString.length,
        type
            + " id "
            + id
            + " exceeds the length of id to string array (size: "
            + idToString.length
            + ")");
    return idToString[id];
  }

  private List<String> readStringList(DataInputStream dataInput, String type) throws IOException {
    int size = dataInput.readInt();
    Preconditions.checkState(size >= 0, "Invalid list size " + size + " for " + type);
    List<String> list = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      list.add(dataInput.readUTF());
    }
    return list;
  }

  /** Convert a folder URL into a relative URL. If the relative URL is empty, use "." instead */
  public static String extractRelativeFolderUrl(String contentUrl, String folderUrl) {
    Preconditions.checkArgument(
        folderUrl.startsWith(contentUrl),
        folderUrl + " doesn't start with the content root url: " + contentUrl);
    String relativeUrl = folderUrl.substring(contentUrl.length());
    return relativeUrl.isEmpty() ? "." : relativeUrl;
  }
}
