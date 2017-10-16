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

def _add_pf4j_plugin_framework(**kwargs):
    kwargs["deps"] = list(depset(kwargs.get("deps", [])).union([
        "//third-party/java/pf4j:pf4j",
    ]))
    kwargs["plugins"] = list(depset(kwargs.get("plugins", [])).union([
        "//third-party/java/pf4j:processor",
    ]))
    kwargs["annotation_processor_params"] = list(depset(kwargs.get("annotation_processor_params", [])).union([
        "pf4j.storageClassName=org.pf4j.processor.ServiceProviderExtensionStorage",
    ]))
    return kwargs

def java_library_with_plugins(name, **kwargs):
    """
    `java_library` that can contain plugins based on pf4j framework
    """

    kwargs_with_immutables = _add_immutables(**kwargs)
    return native.java_library(
      name=name,
      **_add_pf4j_plugin_framework(**kwargs_with_immutables))
