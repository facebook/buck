/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.dex.file;

import com.android.dex.util.ExceptionWithContext;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.file.MixedItemSection.SortType;
import com.android.dx.rop.cst.Constant;
import com.android.dx.rop.cst.CstBaseMethodRef;
import com.android.dx.rop.cst.CstEnumRef;
import com.android.dx.rop.cst.CstFieldRef;
import com.android.dx.rop.cst.CstString;
import com.android.dx.rop.cst.CstType;
import com.android.dx.rop.type.Type;
import com.android.dx.util.ByteArrayAnnotatedOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Adler32;

/**
 * Representation of an entire {@code .dex} (Dalvik EXecutable)
 * file, which itself consists of a set of Dalvik classes.
 */
public final class DexFile {
    /** options controlling the creation of the file */
    private DexOptions dexOptions;

    /** {@code non-null;} word data section */
    private final MixedItemSection wordData;

    /**
     * {@code non-null;} type lists section. This is word data, but separating
     * it from {@link #wordData} helps break what would otherwise be a
     * circular dependency between the that and {@link #protoIds}.
     */
    private final MixedItemSection typeLists;

    /**
     * {@code non-null;} map section. The map needs to be in a section by itself
     * for the self-reference mechanics to work in a reasonably
     * straightforward way. See {@link MapItem#addMap} for more detail.
     */
    private final MixedItemSection map;

    /** {@code non-null;} string data section */
    private final MixedItemSection stringData;

    /** {@code non-null;} string identifiers section */
    private final StringIdsSection stringIds;

    /** {@code non-null;} type identifiers section */
    private final TypeIdsSection typeIds;

    /** {@code non-null;} prototype identifiers section */
    private final ProtoIdsSection protoIds;

    /** {@code non-null;} field identifiers section */
    private final FieldIdsSection fieldIds;

    /** {@code non-null;} method identifiers section */
    private final MethodIdsSection methodIds;

    /** {@code non-null;} class definitions section */
    private final ClassDefsSection classDefs;

    /** {@code non-null;} class data section */
    private final MixedItemSection classData;

    /** {@code non-null;} byte data section */
    private final MixedItemSection byteData;

    /** {@code non-null;} file header */
    private final HeaderSection header;

    /**
     * {@code non-null;} array of sections in the order they will appear in the
     * final output file
     */
    private final Section[] sections;

    /** {@code >= -1;} total file size or {@code -1} if unknown */
    private int fileSize;

    /** {@code >= 40;} maximum width of the file dump */
    private int dumpWidth;

    /**
     * Constructs an instance. It is initially empty.
     */
    public DexFile(DexOptions dexOptions) {
        this.dexOptions = dexOptions;

        header = new HeaderSection(this);
        typeLists = new MixedItemSection(null, this, 4, SortType.NONE);
        wordData = new MixedItemSection("word_data", this, 4, SortType.TYPE);
        stringData =
            new MixedItemSection("string_data", this, 1, SortType.INSTANCE);
        classData = new MixedItemSection(null, this, 1, SortType.NONE);
        byteData = new MixedItemSection("byte_data", this, 1, SortType.TYPE);
        stringIds = new StringIdsSection(this);
        typeIds = new TypeIdsSection(this);
        protoIds = new ProtoIdsSection(this);
        fieldIds = new FieldIdsSection(this);
        methodIds = new MethodIdsSection(this);
        classDefs = new ClassDefsSection(this);
        map = new MixedItemSection("map", this, 4, SortType.NONE);

        /*
         * This is the list of sections in the order they appear in
         * the final output.
         */
        sections = new Section[] {
            header, stringIds, typeIds, protoIds, fieldIds, methodIds,
            classDefs, wordData, typeLists, stringData, byteData,
            classData, map };

        fileSize = -1;
        dumpWidth = 79;
    }

    /**
     * Returns true if this dex doesn't contain any class defs.
     */
    public boolean isEmpty() {
        return classDefs.items().isEmpty();
    }

    /**
     * Gets the dex-creation options object.
     */
    public DexOptions getDexOptions() {
        return dexOptions;
    }

    /**
     * Adds a class to this instance. It is illegal to attempt to add more
     * than one class with the same name.
     *
     * @param clazz {@code non-null;} the class to add
     */
    public void add(ClassDefItem clazz) {
        classDefs.add(clazz);
    }

    /**
     * Gets the class definition with the given name, if any.
     *
     * @param name {@code non-null;} the class name to look for
     * @return {@code null-ok;} the class with the given name, or {@code null}
     * if there is no such class
     */
    public ClassDefItem getClassOrNull(String name) {
        try {
            Type type = Type.internClassName(name);
            return (ClassDefItem) classDefs.get(new CstType(type));
        } catch (IllegalArgumentException ex) {
            // Translate exception, per contract.
            return null;
        }
    }

    /**
     * Writes the contents of this instance as either a binary or a
     * human-readable form, or both.
     *
     * @param out {@code null-ok;} where to write to
     * @param humanOut {@code null-ok;} where to write human-oriented output to
     * @param verbose whether to be verbose when writing human-oriented output
     */
    public void writeTo(OutputStream out, Writer humanOut, boolean verbose)
        throws IOException {
        boolean annotate = (humanOut != null);
        ByteArrayAnnotatedOutput result = toDex0(annotate, verbose);

        if (out != null) {
            out.write(result.getArray());
        }

        if (annotate) {
            result.writeAnnotationsTo(humanOut);
        }
    }

    /**
     * Returns the contents of this instance as a {@code .dex} file,
     * in {@code byte[]} form.
     *
     * @param humanOut {@code null-ok;} where to write human-oriented output to
     * @param verbose whether to be verbose when writing human-oriented output
     * @return {@code non-null;} a {@code .dex} file for this instance
     */
    public byte[] toDex(Writer humanOut, boolean verbose)
        throws IOException {
        boolean annotate = (humanOut != null);
        ByteArrayAnnotatedOutput result = toDex0(annotate, verbose);

        if (annotate) {
            result.writeAnnotationsTo(humanOut);
        }

        return result.getArray();
    }

    /**
     * Sets the maximum width of the human-oriented dump of the instance.
     *
     * @param dumpWidth {@code >= 40;} the width
     */
    public void setDumpWidth(int dumpWidth) {
        if (dumpWidth < 40) {
            throw new IllegalArgumentException("dumpWidth < 40");
        }

        this.dumpWidth = dumpWidth;
    }

    /**
     * Gets the total file size, if known.
     *
     * <p>This is package-scope in order to allow
     * the {@link HeaderSection} to set itself up properly.</p>
     *
     * @return {@code >= 0;} the total file size
     * @throws RuntimeException thrown if the file size is not yet known
     */
    public int getFileSize() {
        if (fileSize < 0) {
            throw new RuntimeException("file size not yet known");
        }

        return fileSize;
    }

    /**
     * Gets the string data section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the string data section
     */
    /*package*/ MixedItemSection getStringData() {
        return stringData;
    }

    /**
     * Gets the word data section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the word data section
     */
    /*package*/ MixedItemSection getWordData() {
        return wordData;
    }

    /**
     * Gets the type lists section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the word data section
     */
    /*package*/ MixedItemSection getTypeLists() {
        return typeLists;
    }

    /**
     * Gets the map section.
     *
     * <p>This is package-scope in order to allow the header section
     * to query it.</p>
     *
     * @return {@code non-null;} the map section
     */
    /*package*/ MixedItemSection getMap() {
        return map;
    }

    /**
     * Gets the string identifiers section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the string identifiers section
     */
    /*package*/ StringIdsSection getStringIds() {
        return stringIds;
    }

    /**
     * Gets the class definitions section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the class definitions section
     */
    /*package*/ ClassDefsSection getClassDefs() {
        return classDefs;
    }

    /**
     * Gets the class data section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the class data section
     */
    /*package*/ MixedItemSection getClassData() {
        return classData;
    }

    /**
     * Gets the type identifiers section.
     *
     * <p>This is public in order to allow
     * the various {@link Item} instances to add items to the
     * instance and help early counting of type ids.</p>
     *
     * @return {@code non-null;} the class identifiers section
     */
    public TypeIdsSection getTypeIds() {
        return typeIds;
    }

    /**
     * Gets the prototype identifiers section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the prototype identifiers section
     */
    /*package*/ ProtoIdsSection getProtoIds() {
        return protoIds;
    }

    /**
     * Gets the field identifiers section.
     *
     * <p>This is public in order to allow
     * the various {@link Item} instances to add items to the
     * instance and help early counting of field ids.</p>
     *
     * @return {@code non-null;} the field identifiers section
     */
    public FieldIdsSection getFieldIds() {
        return fieldIds;
    }

    /**
     * Gets the method identifiers section.
     *
     * <p>This is public in order to allow
     * the various {@link Item} instances to add items to the
     * instance and help early counting of method ids.</p>
     *
     * @return {@code non-null;} the method identifiers section
     */
    public MethodIdsSection getMethodIds() {
        return methodIds;
    }

    /**
     * Gets the byte data section.
     *
     * <p>This is package-scope in order to allow
     * the various {@link Item} instances to add items to the
     * instance.</p>
     *
     * @return {@code non-null;} the byte data section
     */
    /*package*/ MixedItemSection getByteData() {
        return byteData;
    }

    /**
     * Gets the first section of the file that is to be considered
     * part of the data section.
     *
     * <p>This is package-scope in order to allow the header section
     * to query it.</p>
     *
     * @return {@code non-null;} the section
     */
    /*package*/ Section getFirstDataSection() {
        return wordData;
    }

    /**
     * Gets the last section of the file that is to be considered
     * part of the data section.
     *
     * <p>This is package-scope in order to allow the header section
     * to query it.</p>
     *
     * @return {@code non-null;} the section
     */
    /*package*/ Section getLastDataSection() {
        return map;
    }

    /**
     * Interns the given constant in the appropriate section of this
     * instance, or do nothing if the given constant isn't the sort
     * that should be interned.
     *
     * @param cst {@code non-null;} constant to possibly intern
     */
    /*package*/ void internIfAppropriate(Constant cst) {
        if (cst instanceof CstString) {
            stringIds.intern((CstString) cst);
        } else if (cst instanceof CstType) {
            typeIds.intern((CstType) cst);
        } else if (cst instanceof CstBaseMethodRef) {
            methodIds.intern((CstBaseMethodRef) cst);
        } else if (cst instanceof CstFieldRef) {
            fieldIds.intern((CstFieldRef) cst);
        } else if (cst instanceof CstEnumRef) {
            fieldIds.intern(((CstEnumRef) cst).getFieldRef());
        } else if (cst == null) {
            throw new NullPointerException("cst == null");
        }
    }

    /**
     * Gets the {@link IndexedItem} corresponding to the given constant,
     * if it is a constant that has such a correspondence, or return
     * {@code null} if it isn't such a constant. This will throw
     * an exception if the given constant <i>should</i> have been found
     * but wasn't.
     *
     * @param cst {@code non-null;} the constant to look up
     * @return {@code null-ok;} its corresponding item, if it has a corresponding
     * item, or {@code null} if it's not that sort of constant
     */
    /*package*/ IndexedItem findItemOrNull(Constant cst) {
        IndexedItem item;

        if (cst instanceof CstString) {
            return stringIds.get(cst);
        } else if (cst instanceof CstType) {
            return typeIds.get(cst);
        } else if (cst instanceof CstBaseMethodRef) {
            return methodIds.get(cst);
        } else if (cst instanceof CstFieldRef) {
            return fieldIds.get(cst);
        } else {
            return null;
        }
    }

    /**
     * Returns the contents of this instance as a {@code .dex} file,
     * in a {@link ByteArrayAnnotatedOutput} instance.
     *
     * @param annotate whether or not to keep annotations
     * @param verbose if annotating, whether to be verbose
     * @return {@code non-null;} a {@code .dex} file for this instance
     */
    private ByteArrayAnnotatedOutput toDex0(boolean annotate,
            boolean verbose) {
        /*
         * The following is ordered so that the prepare() calls which
         * add items happen before the calls to the sections that get
         * added to.
         */

        classDefs.prepare();
        classData.prepare();
        wordData.prepare();
        byteData.prepare();
        methodIds.prepare();
        fieldIds.prepare();
        protoIds.prepare();
        typeLists.prepare();
        typeIds.prepare();
        stringIds.prepare();
        stringData.prepare();
        header.prepare();

        // Place the sections within the file.

        int count = sections.length;
        int offset = 0;

        for (int i = 0; i < count; i++) {
            Section one = sections[i];
            int placedAt = one.setFileOffset(offset);
            if (placedAt < offset) {
                throw new RuntimeException("bogus placement for section " + i);
            }

            try {
                if (one == map) {
                    /*
                     * Inform the map of all the sections, and add it
                     * to the file. This can only be done after all
                     * the other items have been sorted and placed.
                     */
                    MapItem.addMap(sections, map);
                    map.prepare();
                }

                if (one instanceof MixedItemSection) {
                    /*
                     * Place the items of a MixedItemSection that just
                     * got placed.
                     */
                    ((MixedItemSection) one).placeItems();
                }

                offset = placedAt + one.writeSize();
            } catch (RuntimeException ex) {
                throw ExceptionWithContext.withContext(ex,
                        "...while writing section " + i);
            }
        }

        // Write out all the sections.

        fileSize = offset;
        byte[] barr = new byte[fileSize];
        ByteArrayAnnotatedOutput out = new ByteArrayAnnotatedOutput(barr);

        if (annotate) {
            out.enableAnnotations(dumpWidth, verbose);
        }

        for (int i = 0; i < count; i++) {
            try {
                Section one = sections[i];
                int zeroCount = one.getFileOffset() - out.getCursor();
                if (zeroCount < 0) {
                    throw new ExceptionWithContext("excess write of " +
                            (-zeroCount));
                }
                out.writeZeroes(one.getFileOffset() - out.getCursor());
                one.writeTo(out);
            } catch (RuntimeException ex) {
                ExceptionWithContext ec;
                if (ex instanceof ExceptionWithContext) {
                    ec = (ExceptionWithContext) ex;
                } else {
                    ec = new ExceptionWithContext(ex);
                }
                ec.addContext("...while writing section " + i);
                throw ec;
            }
        }

        if (out.getCursor() != fileSize) {
            throw new RuntimeException("foreshortened write");
        }

        // Perform final bookkeeping.

        calcSignature(barr);
        calcChecksum(barr);

        if (annotate) {
            wordData.writeIndexAnnotation(out, ItemType.TYPE_CODE_ITEM,
                    "\nmethod code index:\n\n");
            getStatistics().writeAnnotation(out);
            out.finishAnnotating();
        }

        return out;
    }

    /**
     * Generates and returns statistics for all the items in the file.
     *
     * @return {@code non-null;} the statistics
     */
    public Statistics getStatistics() {
        Statistics stats = new Statistics();

        for (Section s : sections) {
            stats.addAll(s);
        }

        return stats;
    }

    /**
     * Calculates the signature for the {@code .dex} file in the
     * given array, and modify the array to contain it.
     *
     * @param bytes {@code non-null;} the bytes of the file
     */
    private static void calcSignature(byte[] bytes) {
        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }

        md.update(bytes, 32, bytes.length - 32);

        try {
            int amt = md.digest(bytes, 12, 20);
            if (amt != 20) {
                throw new RuntimeException("unexpected digest write: " + amt +
                                           " bytes");
            }
        } catch (DigestException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Calculates the checksum for the {@code .dex} file in the
     * given array, and modify the array to contain it.
     *
     * @param bytes {@code non-null;} the bytes of the file
     */
    private static void calcChecksum(byte[] bytes) {
        Adler32 a32 = new Adler32();

        a32.update(bytes, 12, bytes.length - 12);

        int sum = (int) a32.getValue();

        bytes[8]  = (byte) sum;
        bytes[9]  = (byte) (sum >> 8);
        bytes[10] = (byte) (sum >> 16);
        bytes[11] = (byte) (sum >> 24);
    }
}
