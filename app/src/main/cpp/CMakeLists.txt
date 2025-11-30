cmake_minimum_required(VERSION 3.18.1)

project("torwrapper")

add_library(torwrapper SHARED
        tor_wrapper.cpp)

find_library(log-lib log)

target_link_libraries(torwrapper
        ${log-lib})
