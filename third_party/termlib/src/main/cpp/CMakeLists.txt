cmake_minimum_required(VERSION 3.18.1)

project("cb_term")

# libvterm static library
add_library(vterm STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/encoding.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/keyboard.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/mouse.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/parser.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/pen.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/screen.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/state.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/unicode.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/vterm.c
)

target_include_directories(vterm PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

target_compile_definitions(vterm PRIVATE
    VTERM_STATIC
)

set_target_properties(vterm PROPERTIES POSITION_INDEPENDENT_CODE ON)

# JNI wrapper library
add_library(termlib SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/Terminal.cpp
)

target_include_directories(termlib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

target_compile_features(termlib PRIVATE cxx_std_17)

if(ANDROID)
    target_link_options(termlib PRIVATE "-Wl,-z,max-page-size=16384")

    target_link_libraries(termlib
        vterm
        android
        log
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(termlib PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(termlib
        vterm
    )
endif()
