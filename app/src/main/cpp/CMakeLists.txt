cmake_minimum_required(VERSION 3.22.1)

project("usb_webcam")

add_link_options("-Wl,--build-id=none")
add_compile_options(-O3 -ffast-math -Wall -Wextra -Wno-unused-parameter)

add_library(${CMAKE_PROJECT_NAME} SHARED
        uvc_gadget.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
