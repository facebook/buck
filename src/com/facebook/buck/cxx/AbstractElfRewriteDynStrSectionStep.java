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

package com.facebook.buck.cxx;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSectionLookupResult;
import com.facebook.buck.cxx.toolchain.elf.ElfStringTable;
import com.facebook.buck.cxx.toolchain.elf.ElfSymbolTable;
import com.facebook.buck.cxx.toolchain.elf.ElfVerDef;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * A step which rewrites the dynamic string using the strings currently being used. This is useful
 * after removing symbols from the dynamic symbol table to compact the string table.
 */
@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfRewriteDynStrSectionStep implements Step {

  private static final String DYNAMIC = ".dynamic";
  private static final String DYNSTR = ".dynstr";
  private static final String DYNSYM = ".dynsym";
  private static final String VERDEF = ".gnu.version_d";

  abstract ProjectFilesystem getFilesystem();

  abstract Path getPath();

  /** @return a processor for the `.dynamic` section. */
  private SectionUsingDynamicStrings getDynamicProcessor(Elf elf) throws IOException {
    return new SectionUsingDynamicStrings() {

      // Load the dynamic section.
      private final ElfSection dynamicSection =
          elf.getMandatorySectionByName(getPath(), DYNAMIC).getSection();
      private final ElfDynamicSection dynamic =
          ElfDynamicSection.parse(elf.header.ei_class, dynamicSection.body);

      @Override
      public ImmutableList<Long> getStringReferences() {
        return RichStream.from(dynamic.entries)
            .filter(e -> e.d_tag.getType() == ElfDynamicSection.DTag.Type.STRING)
            .map(e -> e.d_un)
            .toImmutableList();
      }

      @Override
      public void processNewStringReferences(
          long newSize, ImmutableMap<Long, Long> newStringIndices) {
        ElfDynamicSection newDynamic =
            new ElfDynamicSection(
                RichStream.from(dynamic.entries)
                    .map(
                        e -> {
                          if (e.d_tag == ElfDynamicSection.DTag.DT_STRSZ) {
                            return new ElfDynamicSection.Entry(e.d_tag, newSize);
                          } else if (e.d_tag.getType() == ElfDynamicSection.DTag.Type.STRING) {
                            return new ElfDynamicSection.Entry(
                                e.d_tag, Objects.requireNonNull(newStringIndices.get(e.d_un)));
                          } else {
                            return e;
                          }
                        })
                    .toImmutableList());
        dynamicSection.body.rewind();
        newDynamic.write(elf.header.ei_class, dynamicSection.body);
      }
    };
  }

  /** @return a processor for the dynamic symbol table section. */
  private SectionUsingDynamicStrings getDynSymProcessor(Elf elf) throws IOException {
    return new SectionUsingDynamicStrings() {

      private final ElfSection dynSymSection =
          elf.getMandatorySectionByName(getPath(), DYNSYM).getSection();
      private final ElfSymbolTable dynSym =
          ElfSymbolTable.parse(elf.header.ei_class, dynSymSection.body);

      @Override
      public ImmutableList<Long> getStringReferences() {
        return RichStream.from(dynSym.entries).map(e -> e.st_name).toImmutableList();
      }

      @Override
      public void processNewStringReferences(
          long newSize, ImmutableMap<Long, Long> newStringIndices) {
        // Rewrite the dynamic symbol table.
        ElfSymbolTable newSymbolTable =
            new ElfSymbolTable(
                RichStream.from(dynSym.entries)
                    .map(
                        e ->
                            new ElfSymbolTable.Entry(
                                Objects.requireNonNull(newStringIndices.get(e.st_name)),
                                e.st_info,
                                e.st_other,
                                e.st_shndx,
                                e.st_value,
                                e.st_size))
                    .toImmutableList());
        dynSymSection.body.rewind();
        newSymbolTable.write(elf.header.ei_class, dynSymSection.body);
      }
    };
  }

  /** @return a processor for the GNU version definition section. */
  private Optional<SectionUsingDynamicStrings> getVerdefProcessor(Elf elf) {
    return elf.getSectionByName(VERDEF)
        .map(ElfSectionLookupResult::getSection)
        .map(
            verdefSection ->
                new SectionUsingDynamicStrings() {

                  private final ElfVerDef verdef =
                      ElfVerDef.parse(elf.header.ei_class, verdefSection.body);

                  @Override
                  public ImmutableList<Long> getStringReferences() {
                    return RichStream.from(verdef.entries)
                        .flatMap(vd -> vd.getSecond().stream())
                        .map(vdx -> vdx.vda_name)
                        .toImmutableList();
                  }

                  @Override
                  public void processNewStringReferences(
                      long newSize, ImmutableMap<Long, Long> newStringIndices) {
                    ElfVerDef fixedVerdef =
                        new ElfVerDef(
                            RichStream.from(verdef.entries)
                                .map(
                                    e ->
                                        new Pair<>(
                                            e.getFirst(),
                                            RichStream.from(e.getSecond())
                                                .map(
                                                    ve ->
                                                        new ElfVerDef.Verdaux(
                                                            Objects.requireNonNull(
                                                                newStringIndices.get(ve.vda_name)),
                                                            ve.vda_next))
                                                .toImmutableList()))
                                .toImmutableList());
                    verdefSection.body.rewind();
                    fixedVerdef.write(elf.header.ei_class, verdefSection.body);
                  }
                });
  }

  /** @return a list of all section processors. */
  private ImmutableList<SectionUsingDynamicStrings> getSectionProcesors(Elf elf)
      throws IOException {
    ImmutableList.Builder<SectionUsingDynamicStrings> builder = ImmutableList.builder();
    builder.add(getDynamicProcessor(elf));
    builder.add(getDynSymProcessor(elf));
    getVerdefProcessor(elf).ifPresent(builder::add);
    return builder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            getFilesystem().resolve(getPath()),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      Elf elf = new Elf(buffer);

      ImmutableList<SectionUsingDynamicStrings> processors = getSectionProcesors(elf);

      // Load the dynamic string table.
      ElfSectionLookupResult dynStrSection = elf.getMandatorySectionByName(getPath(), DYNSTR);
      byte[] dynStr = new byte[dynStrSection.getSection().body.remaining()];
      dynStrSection.getSection().body.get(dynStr);

      // Collect all the string references from the section processors.
      ImmutableList<Long> stringIndices =
          RichStream.from(processors)
              .flatMap(p -> p.getStringReferences().stream())
              .toImmutableList();

      // Write the new dynamic string table out to a byte array and get the new string indices
      // corresponding to the order of the collected string indices.
      ByteArrayOutputStream newDynStrStream = new ByteArrayOutputStream();
      ImmutableList<Integer> newStringIndices =
          ElfStringTable.writeStringTableFromStringTable(
              dynStr,
              RichStream.from(stringIndices).map(i -> (int) (long) i).toImmutableList(),
              newDynStrStream);
      Preconditions.checkState(stringIndices.size() == newStringIndices.size());
      byte[] newDynStr = newDynStrStream.toByteArray();
      Preconditions.checkState(dynStrSection.getSection().header.sh_size >= newDynStr.length);

      // Generate a map from old to new string indices which sections can use to update themselves.
      Map<Long, Long> newStringIndexMapBuilder = new HashMap<>();
      for (int i = 0; i < stringIndices.size(); i++) {
        newStringIndexMapBuilder.put(stringIndices.get(i), (long) newStringIndices.get(i));
      }
      ImmutableMap<Long, Long> newStringIndexMap = ImmutableMap.copyOf(newStringIndexMapBuilder);

      // Call back into the processors to update themselves with the new string indices.
      processors.forEach(p -> p.processNewStringReferences(newDynStr.length, newStringIndexMap));

      // Rewrite the dynamic string section.
      dynStrSection.getSection().body.rewind();
      dynStrSection.getSection().body.put(newDynStr);

      // Fixup the version section header with the new size and write it out.
      buffer.position(
          (int) (elf.header.e_shoff + dynStrSection.getIndex() * elf.header.e_shentsize));
      dynStrSection
          .getSection()
          .header
          .withSize(dynStrSection.getSection().body.position())
          .write(elf.header.ei_class, buffer);
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public final String getShortName() {
    return "elf_rewrite_dyn_str_section";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Rewrite the EFL .dynstr section in " + getPath();
  }

  /**
   * Interface for sections which use the dynamic string table, defining how they use it and how to
   * update them when the dynamic string table changes.
   */
  private interface SectionUsingDynamicStrings {

    /** @return all indices into the dynamic string table used by this section. */
    ImmutableList<Long> getStringReferences();

    /** Update the section owned by this processor with the new string indices. */
    void processNewStringReferences(long newSize, ImmutableMap<Long, Long> newStringIndices);
  }
}
