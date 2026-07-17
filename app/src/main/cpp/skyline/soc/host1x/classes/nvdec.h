// SPDX-License-Identifier: MPL-2.0
// Copyright © 2021 Skyline Team and Contributors (https://github.com/skyline-emu/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <common.h>
#include "nvdec/nvdec_registers.h"
#include "nvdec/codec.h"

namespace skyline::soc::host1x {
    /**
     * @brief The shared NVDEC engine, this is shared between all channels as there is a single NVDEC falcon in hardware
     * @note This is locked internally as the NVDEC and VIC channel FIFOs run on separate threads
     */
    class NvDecDevice {
      private:
        const DeviceState &state;
        vdec::NvdecRegisters registers{};
        vdec::Codec codec;
        std::mutex mutex;

        void Execute();

      public:
        NvDecDevice(const DeviceState &state);

        void CallMethod(u32 method, u32 argument);

        /**
         * @brief Returns the next decoded frame, called by the VIC when composing an output surface
         */
        std::unique_ptr<vdec::Frame> GetFrame();
    };

    /**
     * @brief The NVDEC Host1x class implements hardware accelerated video decoding for the VP9/VP8/H264/VC1 codecs
     */
    class NvDecClass {
      private:
        std::function<void()> opDoneCallback;
        NvDecDevice &device;

      public:
        NvDecClass(std::function<void()> opDoneCallback, NvDecDevice &device);

        void CallMethod(u32 method, u32 argument);
    };
}
