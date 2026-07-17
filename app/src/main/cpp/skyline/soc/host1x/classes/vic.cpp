// SPDX-License-Identifier: MPL-2.0
// Copyright © 2021 Skyline Team and Contributors (https://github.com/skyline-emu/)
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

extern "C" {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wconversion"
#include <libswscale/swscale.h>
#pragma GCC diagnostic pop
}

#include <cstring>
#include <soc.h>
#include <gpu/texture/layout.h>
#include "vic.h"

namespace skyline::soc::host1x {
    VicDevice::VicDevice(const DeviceState &state, NvDecDevice &nvDec) : state{state}, nvDec{nvDec} {}

    VicDevice::~VicDevice() {
        if (scalerCtx)
            sws_freeContext(scalerCtx);
    }

    void VicDevice::CallMethod(u32 method, u32 argument) {
        std::scoped_lock lock{mutex};

        // Register values (IOVAs) are passed shifted right by 8, unshift them on write
        const u64 arg{static_cast<u64>(argument) << 8};

        switch (static_cast<Method>(method)) {
            case Method::Execute:
                Execute();
                break;
            case Method::SetConfigStructOffset:
                configStructAddress = arg;
                break;
            case Method::SetOutputSurfaceLumaOffset:
                outputSurfaceLumaAddress = arg;
                break;
            case Method::SetOutputSurfaceChromaOffset:
                outputSurfaceChromaAddress = arg;
                break;
            default:
                LOGV("Unhandled VIC method called: 0x{:X} argument: 0x{:X}", method, argument);
                break;
        }
    }

    void VicDevice::Execute() {
        if (outputSurfaceLumaAddress == 0) {
            LOGE("VIC output surface luma address is not set");
            return;
        }

        auto &smmu{state.soc->smmu};
        const VicConfig config{.raw = smmu.Read<u64>(static_cast<u32>(configStructAddress + 0x20))};

        auto frame{nvDec.GetFrame()};
        if (!frame)
            return;

        const u64 surfaceWidth{static_cast<u64>(config.surfaceWidthMinus1) + 1};
        const u64 surfaceHeight{static_cast<u64>(config.surfaceHeightMinus1) + 1};
        if (static_cast<u64>(frame->GetWidth()) != surfaceWidth || static_cast<u64>(frame->GetHeight()) != surfaceHeight)
            LOGW("Frame dimensions {}x{} don't match surface dimensions {}x{}", frame->GetWidth(), frame->GetHeight(), surfaceWidth, surfaceHeight);

        switch (static_cast<VideoPixelFormat>(config.pixelFormat)) {
            case VideoPixelFormat::Rgba8:
            case VideoPixelFormat::Bgra8:
            case VideoPixelFormat::Rgbx8:
                WriteRgbFrame(std::move(frame), config);
                break;
            case VideoPixelFormat::Yuv420:
                WriteYuvFrame(std::move(frame), config);
                break;
            default:
                LOGE("Unknown VIC video pixel format: 0x{:X}", static_cast<u64>(config.pixelFormat));
                break;
        }
    }

    void VicDevice::WriteRgbFrame(std::unique_ptr<vdec::Frame> frame, const VicConfig &config) {
        const i32 frameWidth{frame->GetWidth()};
        const i32 frameHeight{frame->GetHeight()};
        const AVPixelFormat frameFormat{frame->GetPixelFormat()};

        if (!scalerCtx || frameWidth != scalerWidth || frameHeight != scalerHeight) {
            const AVPixelFormat targetFormat{[&]() {
                switch (static_cast<VideoPixelFormat>(config.pixelFormat)) {
                    case VideoPixelFormat::Rgba8:
                        return AV_PIX_FMT_RGBA;
                    case VideoPixelFormat::Bgra8:
                        return AV_PIX_FMT_BGRA;
                    case VideoPixelFormat::Rgbx8:
                        return AV_PIX_FMT_RGB0;
                    default:
                        return AV_PIX_FMT_RGBA;
                }
            }()};

            sws_freeContext(scalerCtx);
            // Frames are decoded into YUV420, convert to the desired RGB format
            scalerCtx = sws_getContext(frameWidth, frameHeight, frameFormat, frameWidth, frameHeight, targetFormat, 0, nullptr, nullptr, nullptr);
            scalerWidth = frameWidth;
            scalerHeight = frameHeight;
        }

        const size_t frameSize{static_cast<size_t>(frameWidth) * static_cast<size_t>(frameHeight) * 4};
        convertedFrameBuffer.resize(frameSize);

        const std::array<i32, 4> convertedStride{frameWidth * 4, frameHeight * 4, 0, 0};
        u8 *convertedFrameBufAddr{convertedFrameBuffer.data()};
        sws_scale(scalerCtx, frame->GetPlanes(), frame->GetStrides(), 0, frameHeight, &convertedFrameBufAddr, convertedStride.data());

        auto &smmu{state.soc->smmu};

        // Use the minimum of surface/frame dimensions to avoid buffer overflows
        const u32 surfaceWidth{static_cast<u32>(config.surfaceWidthMinus1) + 1};
        const u32 surfaceHeight{static_cast<u32>(config.surfaceHeightMinus1) + 1};
        const u32 width{std::min(surfaceWidth, static_cast<u32>(frameWidth))};
        const u32 height{std::min(surfaceHeight, static_cast<u32>(frameHeight))};

        if (config.blockLinearKind != 0) {
            // Swizzle the pitch-linear frame into a block-linear output surface
            const gpu::texture::Dimensions dimensions{width, height, 1};
            const size_t gobBlockHeight{1UL << config.blockLinearHeightLog2};
            const size_t size{gpu::texture::GetBlockLinearLayerSize(dimensions, 1, 1, 4, gobBlockHeight, 1)};

            lumaBuffer.resize(size);
            gpu::texture::CopyLinearToBlockLinear(dimensions, 1, 1, 4, gobBlockHeight, 1, convertedFrameBufAddr, lumaBuffer.data());

            smmu.Write(static_cast<u32>(outputSurfaceLumaAddress), lumaBuffer.data(), static_cast<u32>(size));
        } else {
            // Write a pitch-linear frame directly
            const u32 linearSize{width * height * 4};
            smmu.Write(static_cast<u32>(outputSurfaceLumaAddress), convertedFrameBufAddr, linearSize);
        }
    }

    void VicDevice::WriteYuvFrame(std::unique_ptr<vdec::Frame> frame, const VicConfig &config) {
        const size_t surfaceWidth{static_cast<size_t>(config.surfaceWidthMinus1) + 1};
        const size_t surfaceHeight{static_cast<size_t>(config.surfaceHeightMinus1) + 1};
        const size_t alignedWidth{(surfaceWidth + 0xFF) & ~0xFFUL};

        // Use the minimum of surface/frame dimensions to avoid buffer overflows
        const size_t frameWidth{std::min(surfaceWidth, static_cast<size_t>(frame->GetWidth()))};
        const size_t frameHeight{std::min(surfaceHeight, static_cast<size_t>(frame->GetHeight()))};

        const auto stride{static_cast<size_t>(frame->GetStride(0))};

        auto &smmu{state.soc->smmu};

        // Luma
        lumaBuffer.resize(alignedWidth * surfaceHeight);
        const u8 *lumaSrc{frame->GetData(0)};
        for (size_t y{}; y < frameHeight; ++y) {
            const size_t src{y * stride};
            const size_t dst{y * alignedWidth};
            std::memcpy(lumaBuffer.data() + dst, lumaSrc + src, frameWidth);
        }
        smmu.Write(static_cast<u32>(outputSurfaceLumaAddress), lumaBuffer.data(), static_cast<u32>(lumaBuffer.size()));

        // Chroma
        chromaBuffer.resize(alignedWidth * surfaceHeight / 2);
        const size_t halfHeight{frameHeight / 2};
        const auto halfStride{static_cast<size_t>(frame->GetStride(1))};

        switch (frame->GetPixelFormat()) {
            case AV_PIX_FMT_YUV420P: {
                // Frame from software decoding, interleave the U/V planes into semiplanar chroma
                const size_t halfWidth{frameWidth / 2};
                u8 *chromaBufferData{chromaBuffer.data()};
                const u8 *chromaBSrc{frame->GetData(1)};
                const u8 *chromaRSrc{frame->GetData(2)};
                for (size_t y{}; y < halfHeight; ++y) {
                    const size_t src{y * halfStride};
                    const size_t dst{y * alignedWidth};
                    for (size_t x{}; x < halfWidth; ++x) {
                        chromaBufferData[dst + x * 2] = chromaBSrc[src + x];
                        chromaBufferData[dst + x * 2 + 1] = chromaRSrc[src + x];
                    }
                }
                break;
            }
            case AV_PIX_FMT_NV12: {
                // Already semiplanar, just copy with the aligned stride
                const u8 *chromaSrc{frame->GetData(1)};
                for (size_t y{}; y < halfHeight; ++y) {
                    const size_t src{y * stride};
                    const size_t dst{y * alignedWidth};
                    std::memcpy(chromaBuffer.data() + dst, chromaSrc + src, frameWidth);
                }
                break;
            }
            default:
                LOGE("Unsupported decoded frame pixel format: {}", static_cast<i32>(frame->GetPixelFormat()));
                return;
        }
        smmu.Write(static_cast<u32>(outputSurfaceChromaAddress), chromaBuffer.data(), static_cast<u32>(chromaBuffer.size()));
    }

    VicClass::VicClass(std::function<void()> opDoneCallback, VicDevice &device)
        : opDoneCallback{std::move(opDoneCallback)}, device{device} {}

    void VicClass::CallMethod(u32 method, u32 argument) {
        device.CallMethod(method, argument);

        // Surface composition is fully synchronous so the operation is done as soon as the method returns
        if (method == static_cast<u32>(VicDevice::Method::Execute))
            opDoneCallback();
    }
}
