def _add_immutables(**kwargs):
    kwargs['deps'] = depset(kwargs.get('deps', [])) + [
        '//src/com/facebook/buck/util/immutables:immutables',
        '//third-party/java/errorprone:error-prone-annotations',
        '//third-party/java/immutables:immutables',
        '//third-party/java/guava:guava',
        '//third-party/java/jsr:jsr305',
    ]
    kwargs['exported_deps'] = depset(kwargs.get('exported_deps', [])) + [
        '//third-party/java/immutables:immutables',
    ]
    kwargs['plugins'] = depset(kwargs.get('plugins', [])) + [
        '//third-party/java/immutables:processor'
    ]
    return kwargs

def java_immutables_library(name, **kwargs):
    return native.java_library(
      name=name,
      **_add_immutables(**kwargs))
