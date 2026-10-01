cmake_minimum_required(VERSION 3.22.1)

project("mobilevm_native")

add_library(
    mobilevm_native
    SHARED
    native_arch.cpp
    native_memory.cpp
    native_disk.cpp
    native_input.cpp
    native_devices.cpp
    native_gic.cpp
    native_cpu_arm64.cpp
    native_cpu_kvm.cpp
    native_cpu_factory.cpp
    guest_linux_shell.cpp
    native_linux_boot.cpp
    native_vm_engine.cpp
    jni_bridge.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    mobilevm_native
    ${log-lib}
    z
)
