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
/* miniz.c v1.15 - public domain deflate/inflate, zlib-subset, ZIP reading/writing/appending, PNG writing
   See "unlicense" statement at the end of this file.
   Rich Geldreich <richgel99@gmail.com>, last updated Oct. 13, 2013
   Implements RFC 1950: http://www.ietf.org/rfc/rfc1950.txt and RFC 1951: http://www.ietf.org/rfc/rfc1951.txt
 */
#include <string.h>
#define MINIZ_HEADER_FILE_ONLY
#include "miniz.c"
#include "miniz-extensions.h"

// Like mz_zip_reader_locate_file, but uses a matching predicate
// instead of a constant name.
static int find_file_match(
    mz_zip_archive *zip_archive,
    name_matcher_t matcher,
    void* userdata)
{
  unsigned int num_files = mz_zip_reader_get_num_files(zip_archive);
  for (unsigned int idx = 0; idx < num_files; idx++) {
    mz_zip_archive_file_stat file_stat;
    if (!mz_zip_reader_file_stat(zip_archive, idx, &file_stat)) {
      return -1;
    }

    if (matcher(userdata, file_stat.m_filename)) {
      return idx;
    }
  }

  return -1;
}

void *mzx_extract_match_to_heap(
    const char *pZip_filename,
    name_matcher_t matcher,
    void* userdata,
    size_t *pSize,
    mz_uint flags)
{
  int file_index;
  mz_zip_archive zip_archive;
  void *p = NULL;

  if (pSize)
    *pSize = 0;

  if ((!pZip_filename) || (!matcher))
    return NULL;

  // Inlined MZ_CLEAR_OBJ(zip_archive);
  memset(&zip_archive, 0, sizeof(zip_archive));
  if (!mz_zip_reader_init_file(
        &zip_archive,
        pZip_filename,
        flags | MZ_ZIP_FLAG_DO_NOT_SORT_CENTRAL_DIRECTORY))
    return NULL;

  if ((file_index = find_file_match(&zip_archive, matcher, userdata)) >= 0)
    p = mz_zip_reader_extract_to_heap(&zip_archive, file_index, pSize, flags);

  mz_zip_reader_end(&zip_archive);
  return p;
}
