def is_buck2():
    host_info = native.host_info()
    return hasattr(host_info, "buck2") and host_info.buck2

def enable_buck2_bootstrap_prebuilts():
    return (
        is_buck2() and
        (native.read_config("java", "enable_bootstrap_prebuilts", "") in ["true", "True", "1", "yes"])
    )
