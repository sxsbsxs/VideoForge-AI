cmake_minimum_required(VERSION 3.22.1)
project(videoforge_rife_jni LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keep the native dependency build limited to the inference features used by RIFE.
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_VULKAN_ONLINE_SPIRV ON CACHE BOOL "" FORCE)
set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_PYTHON OFF CACHE BOOL "" FORCE)
set(NCNN_INSTALL_SDK OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_RTTI ON CACHE BOOL "" FORCE)
set(NCNN_DISABLE_EXCEPTION ON CACHE BOOL "" FORCE)
set(NCNN_PIXEL ON CACHE BOOL "" FORCE)
set(NCNN_STDIO ON CACHE BOOL "" FORCE)
set(NCNN_STRING ON CACHE BOOL "" FORCE)

if(ANDROID)
    find_library(ANDROID_VULKAN_LIBRARY vulkan REQUIRED)
    set(Vulkan_LIBRARY "${ANDROID_VULKAN_LIBRARY}")
endif()

# Pin upstream source and its ncnn/libwebp submodules for reproducible builds.
FetchContent_Declare(
    rife_upstream
    GIT_REPOSITORY https://github.com/nihui/rife-ncnn-vulkan.git
    GIT_TAG a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7
    GIT_SHALLOW TRUE
    GIT_SUBMODULES src/ncnn src/libwebp
    GIT_SUBMODULES_RECURSE TRUE
    GIT_PROGRESS TRUE
)

FetchContent_GetProperties(rife_upstream)
if(NOT rife_upstream_POPULATED)
    FetchContent_Populate(rife_upstream)
endif()

set(RIFE_SOURCE_DIR "${rife_upstream_SOURCE_DIR}/src")
set(RIFE_CMAKE "${RIFE_SOURCE_DIR}/CMakeLists.txt")
set(RIFE_NCNN_CMAKE "${RIFE_SOURCE_DIR}/ncnn/src/CMakeLists.txt")
set(RIFE_CPP "${RIFE_SOURCE_DIR}/rife.cpp")

# The upstream CMake target is a desktop/ADB executable. Turn its RIFE core
# sources into a static library so the Android app can call them through JNI.
file(READ "${RIFE_CMAKE}" RIFE_CMAKE_TEXT)
string(REPLACE
    "find_package(Vulkan REQUIRED)"
    "if(ANDROID)\n    set(Vulkan_LIBRARY \"${ANDROID_VULKAN_LIBRARY}\")\nelse()\n    find_package(Vulkan REQUIRED)\nendif()"
    RIFE_CMAKE_TEXT "${RIFE_CMAKE_TEXT}")
string(REPLACE
    "add_executable(rife-ncnn-vulkan\n    main.cpp\n    rife.cpp\n    warp.cpp\n)"
    "add_library(rife-ncnn-vulkan STATIC\n    rife.cpp\n    warp.cpp\n)"
    RIFE_CMAKE_PATCHED "${RIFE_CMAKE_TEXT}")
if(RIFE_CMAKE_PATCHED STREQUAL RIFE_CMAKE_TEXT)
    message(FATAL_ERROR "Unable to adapt pinned RIFE CMakeLists.txt; upstream source layout changed.")
endif()
file(WRITE "${RIFE_CMAKE}" "${RIFE_CMAKE_PATCHED}")

# Android NDK provides libvulkan.so but CMake's desktop FindVulkan module does
# not consistently publish the imported target on every NDK/CMake combination.
file(READ "${RIFE_NCNN_CMAKE}" NCNN_CMAKE_TEXT)
string(REPLACE
    "    find_package(Vulkan QUIET)"
    "    if(ANDROID)\n        if(NOT TARGET Vulkan::Vulkan)\n            add_library(Vulkan::Vulkan UNKNOWN IMPORTED)\n            set_target_properties(Vulkan::Vulkan PROPERTIES IMPORTED_LOCATION \"${ANDROID_VULKAN_LIBRARY}\")\n        endif()\n        set(Vulkan_FOUND TRUE)\n    else()\n        find_package(Vulkan QUIET)\n    endif()"
    NCNN_CMAKE_PATCHED "${NCNN_CMAKE_TEXT}")
if(NCNN_CMAKE_PATCHED STREQUAL NCNN_CMAKE_TEXT)
    message(FATAL_ERROR "Unable to adapt pinned ncnn Vulkan CMake configuration.")
endif()
file(WRITE "${RIFE_NCNN_CMAKE}" "${NCNN_CMAKE_PATCHED}")

# Make model-load errors visible to the JNI layer instead of silently continuing
# with an uninitialized RIFE network.
file(READ "${RIFE_CPP}" RIFE_CPP_TEXT)
string(REPLACE
    "static void load_param_model(ncnn::Net& net, const std::string& modeldir, const char* name)"
    "static int load_param_model(ncnn::Net& net, const std::string& modeldir, const char* name)"
    RIFE_CPP_TEXT RIFE_CPP_TEXT)
string(REPLACE
    "    net.load_param(parampath);\n    net.load_model(modelpath);"
    "    const int param_status = net.load_param(parampath);\n    if (param_status != 0) return param_status;\n    return net.load_model(modelpath);"
    RIFE_CPP_PATCHED "${RIFE_CPP_TEXT}")
string(REPLACE
    "    load_param_model(flownet, modeldir, \"flownet\");"
    "    if (load_param_model(flownet, modeldir, \"flownet\") != 0) return -1;"
    RIFE_CPP_PATCHED_CHECKED "${RIFE_CPP_PATCHED}")
if(RIFE_CPP_PATCHED_CHECKED STREQUAL RIFE_CPP_TEXT)
    message(FATAL_ERROR "Unable to add RIFE model-load error checking.")
endif()
file(WRITE "${RIFE_CPP}" "${RIFE_CPP_PATCHED_CHECKED}")

add_subdirectory("${RIFE_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/rife-upstream")

add_library(videoforge_rife_jni SHARED rife_jni.cpp)
target_compile_features(videoforge_rife_jni PRIVATE cxx_std_11)
target_include_directories(videoforge_rife_jni PRIVATE "${RIFE_SOURCE_DIR}")
target_link_libraries(videoforge_rife_jni PRIVATE rife-ncnn-vulkan log)

# Preserve upstream license texts in the APK's assets for binary redistribution.
set(LICENSE_ASSET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../assets/licenses")
file(MAKE_DIRECTORY "${LICENSE_ASSET_DIR}")
configure_file("${rife_upstream_SOURCE_DIR}/LICENSE" "${LICENSE_ASSET_DIR}/RIFE-ncnn-vulkan-LICENSE.txt" COPYONLY)
configure_file("${RIFE_SOURCE_DIR}/ncnn/LICENSE.txt" "${LICENSE_ASSET_DIR}/ncnn-LICENSE.txt" COPYONLY)
configure_file("${RIFE_SOURCE_DIR}/libwebp/COPYING" "${LICENSE_ASSET_DIR}/libwebp-COPYING.txt" COPYONLY)
configure_file("${RIFE_SOURCE_DIR}/ncnn/glslang/LICENSE.txt" "${LICENSE_ASSET_DIR}/glslang-LICENSE.txt" COPYONLY)
