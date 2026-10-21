cmake_minimum_required(VERSION 3.22.1)
project("spectratone")

add_library(spectratone SHARED native-engine.cpp)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    spectratone
    ${log-lib}
    ${jnigraphics-lib}
)
