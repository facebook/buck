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

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class SourcePositionJsonTypeAdapter extends TypeAdapter<SourcePosition> {

    private static final String START_LINE = "startLine";

    private static final String START_COLUMN = "startColumn";

    private static final String START_OFFSET = "startOffset";

    private static final String END_LINE = "endLine";

    private static final String END_COLUMN = "endColumn";

    private static final String END_OFFSET = "endOffset";

    @Override
    public void write(JsonWriter out, SourcePosition value) throws IOException {
        int startLine = value.getStartLine();
        int startColumn = value.getStartColumn();
        int startOffset = value.getStartOffset();
        int endLine = value.getEndLine();
        int endColumn = value.getEndColumn();
        int endOffset = value.getEndOffset();
        out.beginObject();
        if (startLine != -1) {
            out.name(START_LINE).value(startLine);
        }
        if (startColumn != -1) {
            out.name(START_COLUMN).value(startColumn);
        }
        if (startOffset != -1) {
            out.name(START_OFFSET).value(startOffset);
        }
        if (endLine != -1 && endLine != startLine) {
            out.name(END_LINE).value(endLine);
        }
        if (endColumn != -1 && endColumn != startColumn) {
            out.name(END_COLUMN).value(endColumn);
        }
        if (endOffset != -1 && endOffset != startOffset) {
            out.name(END_OFFSET).value(endOffset);
        }
        out.endObject();
    }

    @Override
    public SourcePosition read(JsonReader in) throws IOException {
        int startLine = -1, startColumn = -1, startOffset = -1;
        int endLine = -1, endColumn = -1, endOffset = -1;
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            if (name.equals(START_LINE)) {
                startLine = in.nextInt();
            } else if (name.equals(START_COLUMN)) {
                startColumn = in.nextInt();
            } else if (name.equals(START_OFFSET)) {
                startOffset = in.nextInt();
            } else if (name.equals(END_LINE)) {
                endLine = in.nextInt();
            } else if (name.equals(END_COLUMN)) {
                endColumn = in.nextInt();
            } else if (name.equals(END_OFFSET)) {
                endOffset = in.nextInt();
            } else {
                in.skipValue();
            }
        }
        in.endObject();

        endLine = (endLine != -1) ? endLine : startLine;
        endColumn = (endColumn != -1) ? endColumn : startColumn;
        endOffset = (endOffset != -1) ? endOffset : startOffset;
        return new SourcePosition(startLine, startColumn, startOffset, endLine, endColumn,
                endOffset);
    }
}
