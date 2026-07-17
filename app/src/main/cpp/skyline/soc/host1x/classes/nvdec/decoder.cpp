// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2023 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#include "decoder.h"

namespace skyline::soc::host1x::vdec {
    static std::string AvError(int errnum) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE]{};
        av_make_error_string(errbuf, sizeof(errbuf) - 1, errnum);
        return errbuf;
    }

    DecodeApi::~DecodeApi() {
        Reset();
    }

    void DecodeApi::Reset() {
        if (codecContext)
            avcodec_free_context(&codecContext);
        codec = nullptr;
    }

    bool DecodeApi::Initialize(VideoCodec videoCodec) {
        Reset();

        AVCodecID avCodec{[&] {
            switch (videoCodec) {
                case VideoCodec::H264:
                    return AV_CODEC_ID_H264;
                case VideoCodec::Vp8:
                    return AV_CODEC_ID_VP8;
                case VideoCodec::Vp9:
                    return AV_CODEC_ID_VP9;
                default:
                    LOGE("Unsupported video codec {}", static_cast<u64>(videoCodec));
                    return AV_CODEC_ID_NONE;
            }
        }()};

        codec = avcodec_find_decoder(avCodec);
        if (!codec) {
            LOGE("Failed to find a decoder for codec ID {}", static_cast<u32>(avCodec));
            return false;
        }

        codecContext = avcodec_alloc_context3(codec);
        if (!codecContext)
            return false;

        codecContext->thread_count = 0; // Let FFmpeg pick the thread count
        codecContext->thread_type &= ~FF_THREAD_FRAME; // Frame threading would introduce unwanted latency

        if (int ret{avcodec_open2(codecContext, codec, nullptr)}; ret < 0) {
            LOGE("avcodec_open2 error: {}", AvError(ret));
            Reset();
            return false;
        }

        return true;
    }

    bool DecodeApi::SendPacket(span<const u8> packet) {
        if (!codecContext)
            return false;

        AVPacket *avPacket{av_packet_alloc()};
        if (!avPacket)
            return false;

        avPacket->data = const_cast<u8 *>(packet.data());
        avPacket->size = static_cast<i32>(packet.size());

        int ret{avcodec_send_packet(codecContext, avPacket)};
        av_packet_free(&avPacket);

        if (ret < 0) {
            LOGE("avcodec_send_packet error: {}", AvError(ret));
            return false;
        }

        return true;
    }

    void DecodeApi::ReceiveFrames(std::queue<std::unique_ptr<Frame>> &frameQueue) {
        while (true) {
            auto frame{std::make_unique<Frame>()};
            if (int ret{avcodec_receive_frame(codecContext, frame->Get())}; ret < 0) {
                if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF)
                    LOGE("avcodec_receive_frame error: {}", AvError(ret));
                return;
            }

            frameQueue.push(std::move(frame));
        }
    }
}
