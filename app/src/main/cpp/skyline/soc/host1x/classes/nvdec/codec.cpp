// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#include "codec.h"

namespace skyline::soc::host1x::vdec {
    void Codec::Initialize() {
        initialized = decodeApi.Initialize(currentCodec);
    }

    void Codec::SetTargetCodec(VideoCodec codec) {
        if (currentCodec != codec) {
            currentCodec = codec;
            initialized = false;
            LOGI("NVDEC video codec initialized to {}", GetCurrentCodecName());
        }
    }

    void Codec::Decode(SMMU &smmu) {
        if (currentCodec != VideoCodec::H264 && currentCodec != VideoCodec::Vp9) {
            LOGE("Unsupported NVDEC codec: {}", GetCurrentCodecName());
            return;
        }

        const bool isFirstFrame{!initialized};
        if (isFirstFrame)
            Initialize();

        if (!initialized)
            return;

        // Assemble the bitstream
        bool vp9HiddenFrame{};
        const auto packetData{[&]() -> span<const u8> {
            switch (currentCodec) {
                case VideoCodec::H264:
                    return h264Decoder.ComposeFrame(state, smmu, isFirstFrame);
                case VideoCodec::Vp9:
                    vp9Decoder.ComposeFrame(state, smmu);
                    vp9HiddenFrame = vp9Decoder.WasFrameHidden();
                    return vp9Decoder.GetFrameBytes();
                default:
                    return {};
            }
        }()};

        // Send the assembled bitstream to the decoder
        if (!decodeApi.SendPacket(packetData))
            return;

        // Only receive/store visible frames
        if (vp9HiddenFrame)
            return;

        // Receive output frames from the decoder
        decodeApi.ReceiveFrames(frames);

        while (frames.size() > 10) {
            LOGD("ReceiveFrames overflow, dropped frame");
            frames.pop();
        }
    }

    std::unique_ptr<Frame> Codec::GetCurrentFrame() {
        // Sometimes the VIC will request more frames than have been decoded, in this case return a nullptr and don't overwrite previous data
        if (frames.empty())
            return {};

        auto frame{std::move(frames.front())};
        frames.pop();
        return frame;
    }

    std::string_view Codec::GetCurrentCodecName() const {
        switch (currentCodec) {
            case VideoCodec::None:
                return "None";
            case VideoCodec::H264:
                return "H264";
            case VideoCodec::Vp8:
                return "VP8";
            case VideoCodec::H265:
                return "H265";
            case VideoCodec::Vp9:
                return "VP9";
            default:
                return "Unknown";
        }
    }
}
