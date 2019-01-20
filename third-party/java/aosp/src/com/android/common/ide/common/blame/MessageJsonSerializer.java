/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.common.ide.common.blame;

import com.google.common.base.Optional;
import com.google.common.collect.BiMap;
import com.google.common.collect.EnumHashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.IOException;

/**
 * Class to handle json serialization and deserialization of messages.
 *
 * Reads json objects of the form:
 *
 * <pre>
 * {
 *     "kind":"ERROR",
 *     "text":"errorText",
 *     "original":"unparsed error text: Error in some intermediate file",
 *     "sources": [{
 *         "file":"/path/to/source.java",
 *         "position":{
 *             "startLine":1,
 *             "startColumn":2,
 *             "startOffset":3,
 *             "endLine":4,
 *             "endColumn":5,
 *             "endOffset":6
 *         }
 *     }]
 * }</pre>
 *
 * All fields, other than text, may be omitted. They have the following defaults:
 *
 * <table summary="Default values for the message fields.">
 *     <tr><th>Property</th>        <th>Default</th>             <th>Notes</th></tr>
 *     <tr><td>kind (ERROR, WARNING,
 *             INFO, UNKNOWN)</td>  <td>UNKNOWN</td>             <td></td></tr>
 *     <tr><td>text</td>            <td><i>Empty String</i></td> <td>Should not be omitted.</td></tr>
 *     <tr><td>file (Absolute)</td> <td>{} <i>[unknown]</i></td> <td>See {@link SourceFileJsonTypeAdapter}</td></tr>
 *     <tr><td>position</td>        <td>UNKNOWN</td>             <td></td></tr>
 *     <tr><td>startLine,
 *             startColumn,
 *             startOffset</td>     <td>-1 <i>[unknown]</i></td> <td rowspan="2">0-based</td></tr>
 *     <tr><td>endLine,
 *             endColumn,
 *             endOffset</td>       <td>startLine, startColumn,
 *                                      startOffset</td></tr>
 * </table>
 *
 * <b>Notes</b>
 * <ul>
 *     <li>Offset need not be included, if needed by the consumer of the message it can
 *         be derived from the file, line and column.</li>
 *     <li>If line is included and column is not the message will be considered to apply
 *         to the whole line.</li>
 *     <li>A message can have multiple sources.</li>
 * </ul>
 * It also can read legacy serialized objects of the form:
 *
 * <pre>{
 *     "kind":"ERROR",
 *     "text":"errorText",
 *     "sourcePath": "/path/to/source.java",
 *     "position":{
 *         "startLine":1,
 *         "startColumn":2,
 *         "startOffset":3,
 *         "endLine":4,
 *         "endColumn":5,
 *         "endOffset":6
 *     }
 * }</pre>
 *
 * These serializers are implemented using the lower-level TypeAdapter gson API which gives much
 * more control and allow changes to be made without breaking backward compatibility.
 */
public class MessageJsonSerializer extends TypeAdapter<Message> {

    private static final String KIND = "kind";

    private static final String TEXT = "text";

    private static final String SOURCE_FILE_POSITIONS = "sources";

    private static final String RAW_MESSAGE = "original";

    private static final String TOOL_NAME = "tool";

    private static final String LEGACY_SOURCE_PATH = "sourcePath";

    private static final String LEGACY_POSITION = "position";

    private static final BiMap<Message.Kind, String> KIND_STRING_ENUM_MAP;

    static {
        EnumHashBiMap<Message.Kind, String> map = EnumHashBiMap.create(Message.Kind.class);
        map.put(Message.Kind.ERROR, "error");
        map.put(Message.Kind.WARNING, "warning");
        map.put(Message.Kind.INFO, "info");
        map.put(Message.Kind.STATISTICS, "statistics");
        map.put(Message.Kind.UNKNOWN, "unknown");
        map.put(Message.Kind.SIMPLE, "simple");
        KIND_STRING_ENUM_MAP = Maps.unmodifiableBiMap(map);
    }

    private final SourceFilePositionJsonSerializer mSourceFilePositionTypeAdapter;
    private final SourcePositionJsonTypeAdapter mSourcePositionTypeAdapter;

    public MessageJsonSerializer() {
        mSourceFilePositionTypeAdapter = new SourceFilePositionJsonSerializer();
        mSourcePositionTypeAdapter = mSourceFilePositionTypeAdapter.getSourcePositionTypeAdapter();
    }

    @Override
    public void write(JsonWriter out, Message message) throws IOException {
        out.beginObject()
                .name(KIND).value(KIND_STRING_ENUM_MAP.get(message.getKind()))
                .name(TEXT).value(message.getText())
                .name(SOURCE_FILE_POSITIONS).beginArray();
        for (SourceFilePosition position : message.getSourceFilePositions()) {
            mSourceFilePositionTypeAdapter.write(out, position);
        }
        out.endArray();
        if (!message.getRawMessage().equals(message.getText())) {
            out.name(RAW_MESSAGE).value(message.getRawMessage());
        }
        if (message.getToolName().isPresent()) {
            out.name(TOOL_NAME).value(message.getToolName().get());
        }
        out.endObject();
    }

    @Override
    public Message read(JsonReader in) throws IOException {
        in.beginObject();
        Message.Kind kind = Message.Kind.UNKNOWN;
        String text = "";
        String rawMessage = null;
        Optional<String> toolName = Optional.absent();
        ImmutableList.Builder<SourceFilePosition> positions =
                new ImmutableList.Builder<SourceFilePosition>();
        SourceFile legacyFile = SourceFile.UNKNOWN;
        SourcePosition legacyPosition = SourcePosition.UNKNOWN;
        while (in.hasNext()) {
            String name = in.nextName();
            if (name.equals(KIND)) {
                //noinspection StringToUpperCaseOrToLowerCaseWithoutLocale
                Message.Kind theKind = KIND_STRING_ENUM_MAP.inverse()
                        .get(in.nextString().toLowerCase());
                kind = (theKind != null) ? theKind : Message.Kind.UNKNOWN;
            } else if (name.equals(TEXT)) {
                text = in.nextString();
            } else if (name.equals(RAW_MESSAGE)) {
                rawMessage = in.nextString();
            } else if (name.equals(TOOL_NAME)) {
                toolName = Optional.of(in.nextString());
            } else if (name.equals(SOURCE_FILE_POSITIONS)) {
                switch (in.peek()) {
                    case BEGIN_ARRAY:
                        in.beginArray();
                        while(in.hasNext()) {
                            positions.add(mSourceFilePositionTypeAdapter.read(in));
                        }
                        in.endArray();
                        break;
                    case BEGIN_OBJECT:
                        positions.add(mSourceFilePositionTypeAdapter.read(in));
                        break;
                    default:
                        in.skipValue();
                        break;
                }
            } else if (name.equals(LEGACY_SOURCE_PATH)) {
                legacyFile = new SourceFile(new File(in.nextString()));
            } else if (name.equals(LEGACY_POSITION)) {
                legacyPosition = mSourcePositionTypeAdapter.read(in);
            } else {
                in.skipValue();
            }
        }
        in.endObject();

        if (legacyFile != SourceFile.UNKNOWN || legacyPosition != SourcePosition.UNKNOWN) {
            positions.add(new SourceFilePosition(legacyFile, legacyPosition));
        }
        if (rawMessage == null) {
            rawMessage = text;
        }
        ImmutableList<SourceFilePosition> sourceFilePositions = positions.build();
        if (!sourceFilePositions.isEmpty()) {
            return new Message(kind, text, rawMessage, toolName, sourceFilePositions);
        } else {
            return new Message(kind, text, rawMessage, toolName, ImmutableList.of(SourceFilePosition.UNKNOWN));
        }
    }

    public static void registerTypeAdapters(GsonBuilder builder) {
        builder.registerTypeAdapter(SourceFile.class, new SourceFileJsonTypeAdapter());
        builder.registerTypeAdapter(SourcePosition.class, new SourcePositionJsonTypeAdapter());
        builder.registerTypeAdapter(SourceFilePosition.class,
                new SourceFilePositionJsonSerializer());
        builder.registerTypeAdapter(Message.class, new MessageJsonSerializer());
    }
}
