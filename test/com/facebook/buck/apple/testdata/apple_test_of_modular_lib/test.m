#import "private_hdr.h"
#import <stdlib.h>


void test() {
	if (BOSSMACRO) {
		exit(1);
	}
}
