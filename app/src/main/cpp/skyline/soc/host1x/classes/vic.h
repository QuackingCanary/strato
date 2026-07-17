// SPDX-License-Identifier: MPL-2.0
// Copyright © 2021 Skyline Team and Contributors (https://github.com/skyline-emu/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <common.h>
#include "nvdec.h"

struct SwsContext;

namespace skyline::soc::host1x {
    /**
     * @brief The shared VIC engine, this composes decoded NVDEC frames into output surfaces in guest memory
     * @note This is locked internally as the NVDEC and VIC channel FIFOs run on separate threads
     */
    class VicDevice {
      private:
        /**
         * @brief The output pixel format of a VIC operation as specified in the config struct
         */
        enum class VideoPixelFormat : u64 {
            Rgba8 = 0x1F,
            Bgra8 = 0x20,
            Rgbx8 = 0x23,
            Yuv420 = 0x44,
        };

        /**
         * @brief The relevant portion of the VIC config struct located at +0x20, everything else is ignored
         */
        union VicConfig {
            u64 raw{};
            struct {
                u64 pixelFormat : 7;
                u64 chromaLocHoriz : 2;
                u64 chromaLocVert : 2;
                u64 blockLinearKind : 4;
                u64 blockLinearHeightLog2 : 4;
                u64 _pad_ : 13;
                u64 surfaceWidthMinus1 : 14;
                u64 surfaceHeightMinus1 : 14;
            };
        };
        static_assert(sizeof(VicConfig) == sizeof(u64), "VicConfig is an invalid size");

        const DeviceState &state;
        NvDecDevice &nvDec;
        std::mutex mutex;

        u64 configStructAddress{};
        u64 outputSurfaceLumaAddress{};
        u64 outputSurfaceChromaAddress{};

        SwsContext *scalerCtx{};
        i32 scalerWidth{};
        i32 scalerHeight{};

        // These buffers are retained across frames to avoid reallocation, their sizes don't change during a stream
        std::vector<u8> convertedFrameBuffer;
        std::vector<u8> lumaBuffer;
        std::vector<u8> chromaBuffer;

        void Execute();

        void WriteRgbFrame(std::unique_ptr<vdec::Frame> frame, const VicConfig &config);

        void WriteYuvFrame(std::unique_ptr<vdec::Frame> frame, const VicConfig &config);

      public:
        /**
         * @note These are the falcon method IDs written through THI Method0, see NV_PVIC_THI_METHOD0
         */
        enum class Method : u32 {
            Execute = 0xC0,
            SetControlParams = 0x1C1,
            SetConfigStructOffset = 0x1C2,
            SetOutputSurfaceLumaOffset = 0x1C8,
            SetOutputSurfaceChromaOffset = 0x1C9,
            SetOutputSurfaceChromaUnusedOffset = 0x1CA,
        };

        VicDevice(const DeviceState &state, NvDecDevice &nvDec);

        ~VicDevice();

        void CallMethod(u32 method, u32 argument);
    };

    /**
     * @brief The VIC Host1x class implements hardware accelerated image operations
     */
    class VicClass {
      private:
        std::function<void()> opDoneCallback;
        VicDevice &device;

      public:
        VicClass(std::function<void()> opDoneCallback, VicDevice &device);

        void CallMethod(u32 method, u32 argument);
    };
}
