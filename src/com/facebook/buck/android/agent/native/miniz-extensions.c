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
