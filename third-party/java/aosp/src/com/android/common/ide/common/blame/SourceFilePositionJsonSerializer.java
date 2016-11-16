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

public class SourceFilePositionJsonSerializer extends TypeAdapter<SourceFilePosition> {

    private static final String POSITION = "position";

    private static final String FILE = "file";

    private final SourceFileJsonTypeAdapter mSourceFileJsonTypeAdapter;
    private final SourcePositionJsonTypeAdapter mSourcePositionJsonTypeAdapter;

    public SourceFilePositionJsonSerializer() {
        mSourcePositionJsonTypeAdapter = new SourcePositionJsonTypeAdapter();
        mSourceFileJsonTypeAdapter = new SourceFileJsonTypeAdapter();
    }

    @Override
    public SourceFilePosition read(JsonReader in) throws IOException {
        in.beginObject();
        SourceFile file = SourceFile.UNKNOWN;
        SourcePosition position = SourcePosition.UNKNOWN;
        while(in.hasNext()) {
            String name = in.nextName();
            if (name.equals(FILE)) {
                file = mSourceFileJsonTypeAdapter.read(in);
            } else if (name.equals(POSITION)) {
                position = mSourcePositionJsonTypeAdapter.read(in);
            } else {
                in.skipValue();
            }
        }
        in.endObject();
        return new SourceFilePosition(file, position);
    }

    @Override
    public void write(JsonWriter out, SourceFilePosition src) throws IOException {
        out.beginObject();
        SourceFile sourceFile = src.getFile();
        if (!sourceFile.equals(SourceFile.UNKNOWN)) {
            out.name(FILE);
            mSourceFileJsonTypeAdapter.write(out, sourceFile);
        }
        SourcePosition position = src.getPosition();
        if (!position.equals(SourcePosition.UNKNOWN)) {
            out.name(POSITION);
            mSourcePositionJsonTypeAdapter.write(out, position);
        }
        out.endObject();
    }

    /* package */ SourcePositionJsonTypeAdapter getSourcePositionTypeAdapter() {
        return mSourcePositionJsonTypeAdapter;
    }
}
