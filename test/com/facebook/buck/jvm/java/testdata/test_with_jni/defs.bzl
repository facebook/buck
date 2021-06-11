# @nolint
def _java_home():
    return native.read_config("java_test_integration_test", "java_home")

def create_cpp_flags():
    # Hack until Buck can provide the JDK include path automatically.
    # If JAVA_HOME is defined, we will use it.
    # Otherwise, just fake it with typedefs.
    if _java_home():
        cppflags = [
            '-DUSE_JNI_H',
            '-I' + _java_home() + '/include',
            '-I' + _java_home() + '/include/darwin',
            '-I' + _java_home() + '/include/linux',
        ]
    else:
        cppflags = []
