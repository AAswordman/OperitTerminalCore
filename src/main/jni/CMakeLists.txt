cmake_minimum_required(VERSION 3.18.1)

project("pty")

add_library(pty SHARED pty.c)

target_link_options(pty PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_link_libraries(pty log) 
