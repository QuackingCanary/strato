// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2023 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <queue>
#include <common.h>

extern "C" {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wconversion"
#include <libavcodec/avcodec.h>
#pragma GCC diagnostic pop
}

#include "nvdec_registers.h"

namespace skyline::soc::host1x::vdec {
    /**
     * @brief An RAII wrapper around an FFmpeg AVFrame holding a decoded frame
     */
    class Frame {
      private:
        AVFrame *frame;

      public:
        Frame() : frame{av_frame_alloc()} {}

        Frame(const Frame &) = delete;

        Frame &operator=(const Frame &) = delete;

        ~Frame() {
            av_frame_free(&frame);
        }

        AVFrame *Get() {
            return frame;
        }

        i32 GetWidth() const {
            return frame->width;
        }

        i32 GetHeight() const {
            return frame->height;
        }

        AVPixelFormat GetPixelFormat() const {
            return static_cast<AVPixelFormat>(frame->format);
        }

        i32 GetStride(i32 plane) const {
            return frame->linesize[plane];
        }

        u8 *GetData(i32 plane) const {
            return frame->data[plane];
        }

        const u8 *const *GetPlanes() const {
            return frame->data;
        }

        const i32 *GetStrides() const {
            return frame->linesize;
        }
    };

    /**
     * @brief A minimal software decoding API over FFmpeg's libavcodec, used to decode the bitstreams composed from NVDEC state
     */
    class DecodeApi {
      private:
        const AVCodec *codec{};
        AVCodecContext *codecContext{};

      public:
        DecodeApi() = default;

        DecodeApi(const DecodeApi &) = delete;

        DecodeApi &operator=(const DecodeApi &) = delete;

        ~DecodeApi();

        /**
         * @brief (Re)initialises the underlying codec context for the given codec
         * @return If initialisation was successful
         */
        bool Initialize(VideoCodec codec);

        void Reset();

        /**
         * @brief Sends a composed bitstream packet to the decoder
         */
        bool SendPacket(span<const u8> packet);

        /**
         * @brief Receives all pending decoded frames from the decoder into the supplied queue
         */
        void ReceiveFrames(std::queue<std::unique_ptr<Frame>> &frameQueue);
    };
}
