cmake_minimum_required(VERSION 3.22)
project(rhaygold LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT RUBBERBAND_DIR)
  message(FATAL_ERROR "RUBBERBAND_DIR must point to Rubber Band v3.3.0 source tree")
endif()

add_library(rhaygold SHARED
  src/rhay_gold_engine.cpp
  src/rhay_gold_jni.cpp
  ${RUBBERBAND_DIR}/single/RubberBandSingle.cpp
)

target_include_directories(rhaygold PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/src
  ${RUBBERBAND_DIR}
)

target_compile_options(rhaygold PRIVATE -O3)

target_link_libraries(rhaygold PRIVATE log)
