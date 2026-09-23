cmake_minimum_required(VERSION 3.22.1)
project(tvfinder_fatfs C)

add_library(tvfinder_fatfs SHARED
    fatfs/ff.c
    fatfs/ffunicode.c
    usb_fatfs_bridge.c)

target_include_directories(tvfinder_fatfs PRIVATE fatfs)
target_link_libraries(tvfinder_fatfs log)
