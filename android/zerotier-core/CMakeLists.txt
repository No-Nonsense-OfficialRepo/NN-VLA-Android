cmake_minimum_required(VERSION 3.22.1)
project(ZeroTierOneJNI LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ZT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/ZeroTierOne")

set(ZT_SOURCES
    ${ZT_ROOT}/ext/http-parser/http_parser.c
    ${ZT_ROOT}/node/AES.cpp
    ${ZT_ROOT}/node/AES_aesni.cpp
    ${ZT_ROOT}/node/AES_armcrypto.cpp
    ${ZT_ROOT}/node/Bond.cpp
    ${ZT_ROOT}/node/Capability.cpp
    ${ZT_ROOT}/node/CertificateOfMembership.cpp
    ${ZT_ROOT}/node/CertificateOfOwnership.cpp
    ${ZT_ROOT}/node/ECC.cpp
    ${ZT_ROOT}/node/Identity.cpp
    ${ZT_ROOT}/node/IncomingPacket.cpp
    ${ZT_ROOT}/node/InetAddress.cpp
    ${ZT_ROOT}/node/Membership.cpp
    ${ZT_ROOT}/node/Metrics.cpp
    ${ZT_ROOT}/node/Multicaster.cpp
    ${ZT_ROOT}/node/Network.cpp
    ${ZT_ROOT}/node/NetworkConfig.cpp
    ${ZT_ROOT}/node/Node.cpp
    ${ZT_ROOT}/node/OutboundMulticast.cpp
    ${ZT_ROOT}/node/Packet.cpp
    ${ZT_ROOT}/node/PacketMultiplexer.cpp
    ${ZT_ROOT}/node/Path.cpp
    ${ZT_ROOT}/node/Peer.cpp
    ${ZT_ROOT}/node/Poly1305.cpp
    ${ZT_ROOT}/node/Revocation.cpp
    ${ZT_ROOT}/node/Salsa20.cpp
    ${ZT_ROOT}/node/SelfAwareness.cpp
    ${ZT_ROOT}/node/SHA512.cpp
    ${ZT_ROOT}/node/Switch.cpp
    ${ZT_ROOT}/node/Tag.cpp
    ${ZT_ROOT}/node/Topology.cpp
    ${ZT_ROOT}/node/Trace.cpp
    ${ZT_ROOT}/node/Utils.cpp
    ${ZT_ROOT}/osdep/Http.cpp
    ${ZT_ROOT}/osdep/OSUtils.cpp
    ${ZT_ROOT}/java/jni/com_zerotierone_sdk_Node.cpp
    ${ZT_ROOT}/java/jni/ZT_jniutils.cpp
    ${ZT_ROOT}/java/jni/ZT_jnicache.cpp
)

add_library(ZeroTierOneJNI SHARED ${ZT_SOURCES})

target_include_directories(ZeroTierOneJNI PRIVATE
    ${ZT_ROOT}/include
    ${ZT_ROOT}/node
    ${ZT_ROOT}/ext
    ${ZT_ROOT}/ext/prometheus-cpp-lite-1.0/core/include
    ${ZT_ROOT}/ext/prometheus-cpp-lite-1.0/simpleapi/include
    ${ZT_ROOT}/ext/prometheus-cpp-lite-1.0/3rdparty/http-client-lite/include
)

target_compile_options(ZeroTierOneJNI PRIVATE -fexceptions -fno-omit-frame-pointer)
target_link_libraries(ZeroTierOneJNI log)
