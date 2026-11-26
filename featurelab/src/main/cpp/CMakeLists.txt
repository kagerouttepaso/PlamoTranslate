cmake_minimum_required(VERSION 3.22)
project(featurelab)
add_library(featurelab SHARED cpu.cpp)
target_include_directories(featurelab PRIVATE ../../../../core-llm/src/main/cpp)
