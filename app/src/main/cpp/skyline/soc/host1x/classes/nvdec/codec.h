// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <memory>
#include <queue>
#include <string_view>
#include <soc/smmu.h>
#include "nvdec_registers.h"
#include "decoder.h"
#include "h264.h"
#include "vp9.h"

namespace skyline::soc::host1x::vdec {
    /**
     * @brief Dispatches NVDEC frame decodes to the appropriate codec parser and manages the queue of decoded frames
     */
    class Codec {
      private:
        const NvdecRegisters &state; //!< The NVDEC register state the bitstreams are composed from
        VideoCodec currentCodec{VideoCodec::None};
        bool initialized{};

        DecodeApi decodeApi;
        H264 h264Decoder;
        VP9 vp9Decoder;

        std::queue<std::unique_ptr<Frame>> frames;

        void Initialize();

      public:
        Codec(const NvdecRegisters &state) : state{state} {}

        /**
         * @brief Sets the codec which subsequent Decode calls will use
         */
        void SetTargetCodec(VideoCodec codec);

        VideoCodec GetCurrentCodec() const {
            return currentCodec;
        }

        std::string_view GetCurrentCodecName() const;

        /**
         * @brief Composes the current frame's bitstream from the register state and sends it through the decoder, buffering any decoded frames
         */
        void Decode(SMMU &smmu);

        /**
         * @brief Returns the next decoded frame from the frame queue, or nullptr if empty
         */
        std::unique_ptr<Frame> GetCurrentFrame();
    };
}
