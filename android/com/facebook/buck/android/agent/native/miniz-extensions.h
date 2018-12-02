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
#define MINIZ_HEADER_FILE_ONLY
#include "miniz.c"

// Return nonzero for match.
typedef int (*name_matcher_t)(void* userdata, const char* name);

// Based on mz_zip_extract_archive_file_to_heap, but uses find_file_match
// instead of mz_zip_reader_locate_file.
void *mzx_extract_match_to_heap(
    const char *pZip_filename,
    name_matcher_t matcher,
    void* userdata,
    size_t *pSize,
    mz_uint flags);
