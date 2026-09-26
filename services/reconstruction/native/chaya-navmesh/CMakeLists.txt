# chaya-navmesh: Chaya 02's navmesh baker and path query tool, built on the real upstream recastnavigation
# (Recast + Detour, zlib licence). This file pins the exact upstream release by tag and SHA-256; nothing of Recast is
# reimplemented here -- src/main.cpp only reads geometry, calls the library's own build pipeline and serialises its
# output. See docs/navigation.md.
#
# From services/reconstruction:
#   cmake -S native/chaya-navmesh -B native/chaya-navmesh/build -DCMAKE_BUILD_TYPE=Release
#   cmake --build native/chaya-navmesh/build --config Release
# The worker finds it through CHAYA_NAVMESH_BIN or PATH (chaya_worker.toolchain); the worker image builds and installs it.
#
# Offline builds: pass -DFETCHCONTENT_SOURCE_DIR_RECASTNAVIGATION=/path/to/recastnavigation-1.6.0 (an unpacked copy of
# the same release) and nothing is downloaded.

cmake_minimum_required(VERSION 3.16)
project(chaya_navmesh VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

# The one place the Recast/Detour version is chosen. RECASTNAVIGATION_VERSION is compiled into the tool and reported
# by `chaya-navmesh --version`, so every navmesh artifact records the library that actually built it.
set(RECASTNAVIGATION_VERSION "1.6.0")
set(RECASTNAVIGATION_SHA256 "d48ca0121962fa0639502c0f56c4e3ae72f98e55d88727225444f500775c0074")

include(FetchContent)
# recastnavigation 1.6.0 declares cmake_minimum_required(VERSION 3.1), which CMake >= 4 refuses without this.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(RECASTNAVIGATION_DEMO OFF CACHE BOOL "" FORCE)
set(RECASTNAVIGATION_TESTS OFF CACHE BOOL "" FORCE)
set(RECASTNAVIGATION_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  recastnavigation
  URL "https://github.com/recastnavigation/recastnavigation/archive/refs/tags/v${RECASTNAVIGATION_VERSION}.tar.gz"
  URL_HASH "SHA256=${RECASTNAVIGATION_SHA256}"
)
FetchContent_MakeAvailable(recastnavigation)

add_executable(chaya-navmesh src/main.cpp)
target_link_libraries(chaya-navmesh PRIVATE Recast Detour)
target_compile_definitions(chaya-navmesh PRIVATE
  CHAYA_NAVMESH_VERSION="${PROJECT_VERSION}"
  RECASTNAVIGATION_VERSION="${RECASTNAVIGATION_VERSION}")
if(MINGW)
  # A self-contained Windows binary: no libstdc++/libgcc DLLs need to be on PATH next to it.
  target_link_options(chaya-navmesh PRIVATE -static)
endif()

install(TARGETS chaya-navmesh RUNTIME DESTINATION bin)
