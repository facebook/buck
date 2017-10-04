def _add_immutables(**kwargs):
    kwargs['deps'] = list(depset(kwargs.get('deps', [])).union([
        '//src/com/facebook/buck/util/immutables:immutables',
        '//third-party/java/errorprone:error-prone-annotations',
        '//third-party/java/immutables:immutables',
        '//third-party/java/guava:guava',
        '//third-party/java/jsr:jsr305',
    ]))
    kwargs['exported_deps'] = list(depset(kwargs.get('exported_deps', [])).union([
        '//third-party/java/immutables:immutables',
    ]))
    kwargs['plugins'] = list(depset(kwargs.get('plugins', [])).union([
        '//third-party/java/immutables:processor'
    ]))
    return kwargs

def java_immutables_library(name, **kwargs):
    return native.java_library(
      name=name,
      **_add_immutables(**kwargs))
