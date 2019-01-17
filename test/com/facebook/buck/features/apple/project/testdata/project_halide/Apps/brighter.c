#include <stdio.h>
#include <stdlib.h>

int main(int argc, char ** argv) {
    const char * path = NULL;
    for(int i = 0 ;i < argc ; i ++) {
        if (argv[i][0] == '-' && argv[i][1] == 'o') {
            path = argv[i + 1];
        }
        //fprintf(stderr, "%s", argv[i]);
    }

    FILE * f = fopen("/tmp/brighter-output.txt", "w");
    fprintf(f, "%s", path);
    fclose(f);

    char file_path[512];
    snprintf(file_path, sizeof(file_path), "%s/brighter.h", path);
    f = fopen(file_path, "w");
    fclose(f);

    snprintf(file_path, sizeof(file_path), "%s/brighter.o", path);
    f = fopen(file_path, "w");
    fclose(f);
    exit(0);
}