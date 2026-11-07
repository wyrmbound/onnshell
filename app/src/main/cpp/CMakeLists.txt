cmake_minimum_required(VERSION 3.22)
project(bufbridge C)
add_library(bufbridge SHARED bufbridge.c)
target_link_libraries(bufbridge android log)
