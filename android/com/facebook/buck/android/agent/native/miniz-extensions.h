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
