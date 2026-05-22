cmake_minimum_required(VERSION 3.22.1)
project(mob_ble_android_plugin C)

# Plugin-owned native build harness. It compiles the static NIF archive that the
# consuming Mob application links into its final lib<app>.so and registers
# through generated plugin support as mob_ble_nif_nif_init / mob_ble_jni_on_load.
#
# Required arguments:
#   -DOTP_DIR=/path/to/android/otp/release
#   -DERTS_VSN=erts-17.0
#
# Optional arguments:
#   -DMOB_DIR=/path/to/mob

if(NOT DEFINED OTP_DIR OR OTP_DIR STREQUAL "")
    message(FATAL_ERROR "OTP_DIR is required")
endif()

if(NOT DEFINED ERTS_VSN OR ERTS_VSN STREQUAL "")
    message(FATAL_ERROR "ERTS_VSN is required")
endif()

add_library(mob_ble_android STATIC
    mob_ble_nif.c
    mob_ble_jni_hooks.c
)

set_target_properties(mob_ble_android PROPERTIES
    OUTPUT_NAME mob_ble_android
)

target_include_directories(mob_ble_android PRIVATE
    "${OTP_DIR}/${ERTS_VSN}/include"
)

if(DEFINED MOB_DIR AND NOT MOB_DIR STREQUAL "")
    target_include_directories(mob_ble_android PRIVATE
        "${MOB_DIR}/android/jni"
    )
endif()

target_compile_definitions(mob_ble_android PRIVATE
    STATIC_ERLANG_NIF
    STATIC_ERLANG_NIF_LIBNAME=mob_ble_nif
)

target_compile_options(mob_ble_android PRIVATE
    -Os
    -ffunction-sections
    -fdata-sections
    -fPIC
)
