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
package com.facebook.buck.macho;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.bsd.UnixArchive;
import com.facebook.buck.util.bsd.UnixArchiveEntry;
import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ObjectPathsAbsolutifier {
  private static final Logger LOG = Logger.get(ObjectPathsAbsolutifier.class);

  private final RandomAccessFile file;
  private final ProjectFilesystem filesystem;
  private final ImmutableSet<Path> knownRoots;
  private final String oldCompDir;
  private final String newCompDir;
  private ByteBuffer buffer;
  private final NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder;

  public ObjectPathsAbsolutifier(
      RandomAccessFile file,
      String oldCompDir,
      String newCompDir,
      ProjectFilesystem filesystem,
      ImmutableSet<Path> knownRoots,
      NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder)
      throws IOException {
    Path compDir = Paths.get(newCompDir);
    Preconditions.checkArgument(compDir.isAbsolute());
    Preconditions.checkArgument(compDir.equals(filesystem.getRootPath()));
    this.file = file;
    this.filesystem = filesystem;
    this.oldCompDir = oldCompDir;
    this.newCompDir = newCompDir;
    this.knownRoots = knownRoots;
    this.nulTerminatedCharsetDecoder = nulTerminatedCharsetDecoder;
    remapBuffer();
  }

  private void remapBuffer() throws IOException {
    this.buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, file.length());
  }

  public void updatePaths() throws IOException {
    MachoMagicInfo magicInfo = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    if (!magicInfo.isValidMachMagic()) {
      throw new IOException("Cannot locate magic for Mach O binary.");
    }
    if (magicInfo.isFatBinaryHeaderMagic()) {
      throw new IOException("Fat binaries are not supported at this level.");
    }
    buffer.order(magicInfo.isSwapped() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    processThinBinary(magicInfo);
  }

  private void processThinBinary(final MachoMagicInfo magicInfo) throws IOException {
    Optional<Pair<LinkEditDataCommand, ByteBuffer>> codeSignatureData =
        getCodeSignatureDataToRelocate();

    updateBinaryUuid();
    int stringTableSizeIncrease = updateStringTableContents(magicInfo);
    Optional<LinkEditDataCommand> updatedCodeSignatureCommand =
        restoreOriginalCodeSignatureData(codeSignatureData, stringTableSizeIncrease);
    updateLinkeditSegment(updatedCodeSignatureCommand);
  }

  private Optional<Pair<LinkEditDataCommand, ByteBuffer>> getCodeSignatureDataToRelocate() {

    buffer.position(0);
    ImmutableList<SymTabCommand> symTabCommands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer, nulTerminatedCharsetDecoder, SymTabCommand.class);
    Preconditions.checkArgument(symTabCommands.size() <= 1, "Found more that one SymTabCommand");
    if (symTabCommands.size() == 0) {
      LOG.verbose(
          "SymTabCommand was not found, so there is no need to work with "
              + "LinkEditDataCommand to fix code sign, as string table was not found");
      return Optional.empty();
    }

    buffer.position(0);
    ImmutableList<LinkEditDataCommand> linkEditDataCommands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer, nulTerminatedCharsetDecoder, LinkEditDataCommand.class);
    ImmutableList<LinkEditDataCommand> codeSignatureCommands =
        FluentIterable.from(linkEditDataCommands)
            .filter(
                input ->
                    input
                        .getLoadCommandCommonFields()
                        .getCmd()
                        .equals(LinkEditDataCommand.LC_CODE_SIGNATURE))
            .toList();
    if (codeSignatureCommands.size() == 0) {
      LOG.verbose("LinkEditDataCommand for code signature was not found");
      return Optional.empty();
    }
    Preconditions.checkArgument(
        codeSignatureCommands.size() == 1, "Found more than one LC_CODE_SIGNATURE");

    SymTabCommand symTabCommand = symTabCommands.get(0);
    LinkEditDataCommand codeSignatureCommand = codeSignatureCommands.get(0);

    if (symTabCommand.getStroff().intValue() >= codeSignatureCommand.getDataoff().intValue()) {
      LOG.verbose(
          "String table location > Code signature data location. "
              + "Skipping code signature relocation.");
      return Optional.empty();
    }
    Preconditions.checkArgument(
        symTabCommand.getStroff().plus(symTabCommand.getStrsize()).intValue()
            < codeSignatureCommand.getDataoff().intValue(),
        "String table offset+size overlaps with code signature, something is wrong!");

    byte[] contents = new byte[codeSignatureCommand.getDatasize().intValue()];
    Preconditions.checkArgument(contents.length > 0, "Contents of code signature is 0 bytes");
    buffer.position(codeSignatureCommand.getDataoff().intValue());
    buffer.get(contents);

    return Optional.of(
        new Pair<>(codeSignatureCommand, ByteBuffer.wrap(contents).order(buffer.order())));
  }

  private void updateBinaryUuid() {
    buffer.position(0);
    ImmutableList<UUIDCommand> commands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer, nulTerminatedCharsetDecoder, UUIDCommand.class);
    Preconditions.checkArgument(
        commands.size() == 1, "Found %d UUIDCommands, expected 1", commands.size());

    UUIDCommand uuidCommand = commands.get(0);
    UUIDCommand updatedCommand = uuidCommand.withUuid(UUID.randomUUID());
    UUIDCommandUtils.updateUuidCommand(buffer, uuidCommand, updatedCommand);
  }

  private int updateStringTableContents(final MachoMagicInfo magicInfo) throws IOException {
    return processSymTabCommand(magicInfo, getSymTabCommand());
  }

  private SymTabCommand getSymTabCommand() {
    buffer.position(0);
    ImmutableList<SymTabCommand> commands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer, nulTerminatedCharsetDecoder, SymTabCommand.class);
    Preconditions.checkArgument(
        commands.size() == 1, "Found %d SymTabCommands, expected 1", commands.size());
    return commands.get(0);
  }

  private void updateLinkeditSegment(Optional<LinkEditDataCommand> updatedCodeSignatureCommand) {
    buffer.position(0);
    ImmutableList<SegmentCommand> commands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer, nulTerminatedCharsetDecoder, SegmentCommand.class);

    for (SegmentCommand segmentCommand : commands) {
      if (segmentCommand.getSegname().equals(CommandSegmentSectionNames.SEGMENT_LINKEDIT)) {
        processLinkeditSegmentCommand(segmentCommand, updatedCodeSignatureCommand);
        break;
      }
    }
  }

  private Optional<LinkEditDataCommand> restoreOriginalCodeSignatureData(
      Optional<Pair<LinkEditDataCommand, ByteBuffer>> codeSignatureData,
      int stringTableSizeIncrease)
      throws IOException {
    if (!codeSignatureData.isPresent()) {
      LOG.info("Code had no code signature to relocate, skipping code signature update");
      return Optional.empty();
    }
    if (stringTableSizeIncrease == 0) {
      LOG.info("String table size did not change, skipping code signature update");
      return Optional.empty();
    }
    LinkEditDataCommand command = codeSignatureData.get().getFirst();
    ByteBuffer contents = codeSignatureData.get().getSecond();
    contents.position(0);

    Preconditions.checkArgument(contents.capacity() > 0, "Contents of code signature is 0 bytes");

    SymTabCommand symTabCommand = getSymTabCommand();

    /**
     * Code signature is aligned right after SymTabCommand's string table. Thus it is incorrect to
     * just take previous code signature position and move it to the new place. We need to calculate
     * new position by aligning the position of the first byte after string table.
     */
    int unalignedCodeSignatureOffset =
        symTabCommand.getStroff().intValue() + symTabCommand.getStrsize().intValue();
    int alignedCodeSignatureOffset =
        LinkEditDataCommandUtils.alignCodeSignatureOffsetValue(unalignedCodeSignatureOffset);
    LinkEditDataCommand updated =
        command.withDataoff(UnsignedInteger.fromIntBits(alignedCodeSignatureOffset));
    updateFileSizeTo(updated.getDataoff().plus(updated.getDatasize()).intValue());
    LOG.verbose("Re-positioning code signature to " + updated.getDataoff().intValue());
    buffer.position(updated.getDataoff().intValue());
    buffer.put(contents);
    LOG.verbose("Updating LC_CODE_SIGNATURE for new code signature position");
    LinkEditDataCommandUtils.updateLinkEditDataCommand(buffer, command, updated);

    return Optional.of(updated);
  }

  private void processLinkeditSegmentCommand(
      SegmentCommand original, Optional<LinkEditDataCommand> updatedCodeSignatureCommand) {
    SymTabCommand symTabCommand = getSymTabCommand();
    int fileSize = symTabCommand.getStroff().intValue() + symTabCommand.getStrsize().intValue();
    if (updatedCodeSignatureCommand.isPresent()) {
      LinkEditDataCommand codeSignCommand = updatedCodeSignatureCommand.get();
      /**
       * If code signature is present, append it's size plus size of the gap between string table
       * and code signature itself that can be caused by the aligning of the code signature.
       */
      fileSize = codeSignCommand.getDataoff().intValue() + codeSignCommand.getDatasize().intValue();
    }

    fileSize -= original.getFileoff().intValue();

    UnsignedLong updatedFileSize = UnsignedLong.valueOf(fileSize);
    UnsignedLong updatedVmSize =
        UnsignedLong.fromLongBits(SegmentCommandUtils.alignValue(updatedFileSize.intValue()));
    SegmentCommand updated = original.withFilesize(updatedFileSize).withVmsize(updatedVmSize);
    SegmentCommandUtils.updateSegmentCommand(buffer, original, updated);
  }

  private int processSymTabCommand(MachoMagicInfo magicInfo, SymTabCommand symTabCommand)
      throws IOException {
    UnsignedInteger originalStringTableSize = symTabCommand.getStrsize();

    HashMap<Path, Path> originalToUpdatedPathMap = new HashMap<>();

    // If an SO entry has a string ending in /, then the next symbol
    // is a continuation of this path.  That shouldn't be fixed.
    boolean lastEntryWasContinuation = false;

    for (int idx = 0; idx < symTabCommand.getNsyms().intValue(); idx++) {
      Nlist nlist =
          SymTabCommandUtils.getNlistAtIndex(buffer, symTabCommand, idx, magicInfo.is64Bit());

      final boolean stabIsSourceOrHeaderFile =
          nlist.getN_type().equals(Stab.N_SO) || nlist.getN_type().equals(Stab.N_SOL);
      final boolean stabIsObjectFile = nlist.getN_type().equals(Stab.N_OSO);

      if (!stabIsSourceOrHeaderFile && !stabIsObjectFile) {
        continue;
      }

      Preconditions.checkArgument(
          !SymTabCommandUtils.stringTableEntryIsNull(nlist),
          "Path to object file is `null` string, this is unexpected.");

      if (SymTabCommandUtils.stringTableEntryIsEmptyString(buffer, symTabCommand, nlist)) {
        continue;
      }

      boolean entryIsContinuation = lastEntryWasContinuation;
      lastEntryWasContinuation =
          SymTabCommandUtils.stringTableEntryEndsWithSlash(buffer, symTabCommand, nlist);

      if (entryIsContinuation) {
        // If this entry is a continuation, nothing to do, the first
        // entry in the sequence would have been adjusted as needed.
        continue;
      }

      if (SymTabCommandUtils.stringTableEntryStartsWithSlash(buffer, symTabCommand, nlist)
          && !stabIsObjectFile) {
        // already absolute, skipping
        continue;
      }

      String stringPath =
          SymTabCommandUtils.getStringTableEntryForNlist(
              buffer, symTabCommand, nlist, nulTerminatedCharsetDecoder);

      Path absolutePath = getAbsolutePath(stringPath);
      // absolutePathString is the string that will be used as a value inside binary. It may be
      // different from absolutePath.toString() because the first one is absolute path to the
      // Mach O file, and the last one is absolute path for the loader to the object file.
      // Examples:
      //   absolute path to library is /path/to/lib.a
      //   absolutePathString to object file in library: /path/to/lib.a(somefile.o)
      //
      //   absolute path to a continuation part of the path: /path/to/folder
      //   absolutePathString to a continuation part of the path: /path/to/folder/
      //   (as the next symbol will contain continuation of this path, e.g. file.cpp)
      String absolutePathString;

      if (stabIsSourceOrHeaderFile) {
        // source and header files should not be unsanitized
        absolutePathString = absolutePath.toString();
      } else {
        // object files need to be unsanitized
        Path relativePath =
            getAbsolutePath(filesystem.getRootPath().toString()).relativize(absolutePath);
        Path unsanitizedAbsolutePath = getUnsanitizedAbsolutePath(relativePath);
        absolutePathString = unsanitizedAbsolutePath.toString();
        if (absolutePath.toFile().exists()
            && relativePath.startsWith(filesystem.getBuckPaths().getGenDir().toString())) {
          originalToUpdatedPathMap.put(absolutePath, unsanitizedAbsolutePath);
        } else {
          Optional<String> archiveEntryName = getArchiveEntryNameFromPath(absolutePath);
          if (archiveEntryName.isPresent()) {
            Path sourceArchivePath = getArchivePathFromPath(absolutePath);
            Path targetArchivePath = getArchivePathFromPath(unsanitizedAbsolutePath);
            originalToUpdatedPathMap.put(sourceArchivePath, targetArchivePath);
          }
        }
      }

      if (lastEntryWasContinuation) {
        absolutePathString += "/";
      }

      symTabCommand =
          updateSymTabCommandByUpdatingNlistEntry(
              magicInfo, symTabCommand, nlist, absolutePath, absolutePathString);
    }

    unsanitizeObjectFiles(ImmutableMap.copyOf(originalToUpdatedPathMap));
    return symTabCommand.getStrsize().minus(originalStringTableSize).intValue();
  }

  private SymTabCommand updateSymTabCommandByUpdatingNlistEntry(
      MachoMagicInfo magicInfo,
      SymTabCommand symTabCommand,
      Nlist nlist,
      Path absolutePath,
      String absolutePathString)
      throws IOException {
    extendFileSizeToFitNewString(absolutePathString);

    UnsignedInteger newEntryLocation =
        SymTabCommandUtils.insertNewStringTableEntry(buffer, symTabCommand, absolutePathString);
    LOG.debug("Inserted new string table entry at %s", newEntryLocation);

    updateNlistEntryWithNewContentsLocation(
        buffer, magicInfo, nlist, absolutePath, newEntryLocation);
    LOG.debug("Updated nlist entry to use new string table entry");

    symTabCommand =
        SymTabCommandUtils.updateSymTabCommand(buffer, symTabCommand, absolutePathString);
    LOG.debug("Updated SymTabCommand");
    return symTabCommand;
  }

  private void unsanitizeObjectFiles(ImmutableMap<Path, Path> originalToUpdatedPathMap)
      throws IOException {
    for (Map.Entry<Path, Path> entry : originalToUpdatedPathMap.entrySet()) {
      Path source = entry.getKey();
      if (Files.isDirectory(source)) {
        continue;
      }
      Path destination = entry.getValue();

      if (Files.notExists(destination.getParent())) {
        Files.createDirectories(destination.getParent());
      }
      Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
      if (!destination.toFile().setLastModified(source.toFile().lastModified())) {
        LOG.warn("Unable to set modification date for file %s", source);
      }

      if (destination.getFileName().toString().endsWith(".o")) {
        CompDirReplacer.replaceCompDirInFile(
            destination, oldCompDir, newCompDir, nulTerminatedCharsetDecoder);
      } else if (destination.getFileName().toString().endsWith(".a")) {
        fixCompDirInStaticLibrary(destination);
      }
    }
  }

  private void fixCompDirInStaticLibrary(Path destination) throws IOException {
    FileChannel channel =
        FileChannel.open(destination, StandardOpenOption.READ, StandardOpenOption.WRITE);
    ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
    if (!UnixArchive.checkHeader(buffer)) {
      LOG.warn("Static library at %s has wrong header, skipping", destination);
      return;
    }

    UnixArchive archive = new UnixArchive(channel, nulTerminatedCharsetDecoder);
    for (UnixArchiveEntry archiveEntry : archive.getEntries()) {
      if (archiveEntry.getFileName().endsWith(".o")) {
        MappedByteBuffer map = archive.getMapForEntry(archiveEntry);
        CompDirReplacer replacer = new CompDirReplacer(map, nulTerminatedCharsetDecoder);
        replacer.replaceCompDir(oldCompDir, newCompDir);
      }
    }
    archive.close();
  }

  private void updateFileSizeTo(int newSize) throws IOException {
    ByteOrder order = buffer.order();
    int position = buffer.position();
    file.setLength(newSize);
    remapBuffer();
    buffer.order(order);
    buffer.position(position);
  }

  private void extendFileSizeToFitNewDataWithLength(int length) throws IOException {
    updateFileSizeTo((int) (file.length() + length));
  }

  private void extendFileSizeToFitNewString(String string) throws IOException {
    extendFileSizeToFitNewDataWithLength(
        SymTabCommandUtils.sizeOfStringTableEntryWithContents(string));
  }

  private Path getUnsanitizedAbsolutePath(Path relativePath) {
    return filesystem
        .resolve(filesystem.getBuckPaths().getScratchDir().resolve(relativePath))
        .normalize();
  }

  private Path getArchivePathFromPath(Path path) {
    String string = path.toString();
    if (string.endsWith(")")) {
      path = Paths.get(string.substring(0, string.lastIndexOf("(")));
    }
    return path;
  }

  private Optional<String> getArchiveEntryNameFromPath(Path path) {
    String string = path.toString();
    if (path.toString().endsWith(")")) {
      return Optional.of(string.substring(string.lastIndexOf("(")));
    }
    return Optional.empty();
  }

  private Path getAbsolutePath(String stringPath) throws IOException {
    // First do some parsing
    Path path = Paths.get(stringPath);
    Optional<String> archiveEntryName = getArchiveEntryNameFromPath(path);
    path = getArchivePathFromPath(path);

    Path absolutePath = filesystem.resolve(path);
    if (absolutePath.toFile().exists()) {
      absolutePath = absolutePath.toRealPath();
    } else {
      for (Path otherCells : knownRoots) {
        Path otherCellPath = otherCells.resolve(path);
        if (otherCellPath.toFile().exists()) {
          absolutePath = otherCellPath.toRealPath();
        }
      }
    }
    if (archiveEntryName.isPresent()) {
      absolutePath = Paths.get(absolutePath.toString() + archiveEntryName.get());
    }
    return absolutePath.normalize();
  }

  private void updateNlistEntryWithNewContentsLocation(
      ByteBuffer buffer,
      MachoMagicInfo magicInfo,
      Nlist nlist,
      Path path,
      UnsignedInteger newEntryLocation) {
    Nlist updatedNlist = nlist.withN_strx(newEntryLocation);
    // only object source files need to have a timestamp as their values
    if (nlist.getN_type().equals(Stab.N_OSO) && path.toFile().isFile()) {
      long lastModificationDate = path.toFile().lastModified() / 1000;
      LOG.debug("Updating modification date: %d", lastModificationDate);
      updatedNlist = updatedNlist.withN_value(UnsignedLong.valueOf(lastModificationDate));
    }
    NlistUtils.updateNlistEntry(buffer, nlist, updatedNlist, magicInfo.is64Bit());
  }
}
