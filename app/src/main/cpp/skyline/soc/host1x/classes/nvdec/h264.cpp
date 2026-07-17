// SPDX-License-Identifier: MIT
// Copyright © Ryujinx Team and Contributors (https://ryujinx.org/)
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#include <array>
#include <bit>
#include <cstring>
#include "h264.h"

namespace skyline::soc::host1x::vdec {
    namespace {
        // ZigZag LUTs from libavcodec
        constexpr std::array<u8, 64> ZigZagDirect{
            0,  1,  8,  16, 9,  2,  3,  10, 17, 24, 32, 25, 18, 11, 4,  5,  12, 19, 26, 33, 40, 48,
            41, 34, 27, 20, 13, 6,  7,  14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23,
            30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        };

        constexpr std::array<u8, 16> ZigZagScan{
            0 + 0 * 4, 1 + 0 * 4, 0 + 1 * 4, 0 + 2 * 4, 1 + 1 * 4, 2 + 0 * 4, 3 + 0 * 4, 2 + 1 * 4,
            1 + 2 * 4, 0 + 3 * 4, 1 + 3 * 4, 2 + 2 * 4, 3 + 1 * 4, 3 + 2 * 4, 2 + 3 * 4, 3 + 3 * 4,
        };
    }

    span<const u8> H264::ComposeFrame(const NvdecRegisters &state, SMMU &smmu, bool isFirstFrame) {
        H264DecoderContext context{};
        smmu.Read(reinterpret_cast<u8 *>(&context), static_cast<u32>(state.pictureInfoOffset), static_cast<u32>(sizeof(H264DecoderContext)));

        const i64 frameNumber{static_cast<i64>(context.h264ParameterSet.flags.frameNumber)};
        if (!isFirstFrame && frameNumber != 0) {
            frame.resize(context.streamLen);
            smmu.Read(frame.data(), static_cast<u32>(state.frameBitstreamOffset), context.streamLen);
            return frame;
        }

        // Encode the SPS
        H264BitWriter writer;
        writer.WriteU(1, 24);
        writer.WriteU(0, 1);
        writer.WriteU(3, 2);
        writer.WriteU(7, 5);
        writer.WriteU(100, 8);
        writer.WriteU(0, 8);
        writer.WriteU(31, 8);
        writer.WriteUe(0);

        const auto chromaFormatIdc{static_cast<u32>(context.h264ParameterSet.flags.chromaFormatIdc)};
        writer.WriteUe(chromaFormatIdc);
        if (chromaFormatIdc == 3)
            writer.WriteBit(false);

        writer.WriteUe(0);
        writer.WriteUe(0);
        writer.WriteBit(false); // QpprimeYZeroTransformBypassFlag
        writer.WriteBit(false); // Scaling matrix present flag

        writer.WriteUe(static_cast<u32>(context.h264ParameterSet.flags.log2MaxFrameNumMinus4));

        const auto picOrderCntType{static_cast<u32>(context.h264ParameterSet.flags.picOrderCntType)};
        writer.WriteUe(picOrderCntType);
        if (picOrderCntType == 0) {
            writer.WriteUe(static_cast<u32>(context.h264ParameterSet.log2MaxPicOrderCntLsbMinus4));
        } else if (picOrderCntType == 1) {
            writer.WriteBit(context.h264ParameterSet.deltaPicOrderAlwaysZeroFlag != 0);
            writer.WriteSe(0);
            writer.WriteSe(0);
            writer.WriteUe(0);
        }

        const i32 picHeight{static_cast<i32>(context.h264ParameterSet.frameHeightInMapUnits) /
                            (context.h264ParameterSet.frameMbsOnlyFlag ? 1 : 2)};

        writer.WriteUe(16); // Max number of reference frames
        writer.WriteBit(false);
        writer.WriteUe(context.h264ParameterSet.picWidthInMbs - 1);
        writer.WriteUe(static_cast<u32>(picHeight - 1));
        writer.WriteBit(context.h264ParameterSet.frameMbsOnlyFlag != 0);

        if (!context.h264ParameterSet.frameMbsOnlyFlag)
            writer.WriteBit(context.h264ParameterSet.flags.mbaffFrame != 0);

        writer.WriteBit(context.h264ParameterSet.flags.direct8x8Inference != 0);
        writer.WriteBit(false); // Frame cropping flag
        writer.WriteBit(false); // VUI parameter present flag

        writer.End();

        // Encode the PPS
        writer.WriteU(1, 24);
        writer.WriteU(0, 1);
        writer.WriteU(3, 2);
        writer.WriteU(8, 5);

        writer.WriteUe(0);
        writer.WriteUe(0);

        writer.WriteBit(context.h264ParameterSet.entropyCodingModeFlag != 0);
        writer.WriteBit(context.h264ParameterSet.picOrderPresentFlag != 0);
        writer.WriteUe(0);
        writer.WriteUe(static_cast<u32>(context.h264ParameterSet.numRefIdxL0DefaultActive));
        writer.WriteUe(static_cast<u32>(context.h264ParameterSet.numRefIdxL1DefaultActive));
        writer.WriteBit(context.h264ParameterSet.flags.weightedPred != 0);
        writer.WriteU(static_cast<i32>(context.h264ParameterSet.flags.weightedBipredIdc), 2);
        writer.WriteSe(static_cast<i32>(context.h264ParameterSet.flags.picInitQpMinus26));
        writer.WriteSe(0);
        writer.WriteSe(static_cast<i32>(context.h264ParameterSet.flags.chromaQpIndexOffset));
        writer.WriteBit(context.h264ParameterSet.deblockingFilterControlPresentFlag != 0);
        writer.WriteBit(context.h264ParameterSet.flags.constrainedIntraPred != 0);
        writer.WriteBit(context.h264ParameterSet.redundantPicCntPresentFlag != 0);
        writer.WriteBit(context.h264ParameterSet.transform8x8ModeFlag != 0);

        writer.WriteBit(true); // pic_scaling_matrix_present_flag

        for (i32 index{}; index < 6; index++) {
            writer.WriteBit(true);
            writer.WriteScalingList(span<const u8>{context.weightScale}, index * 16, 16);
        }

        if (context.h264ParameterSet.transform8x8ModeFlag) {
            for (i32 index{}; index < 2; index++) {
                writer.WriteBit(true);
                writer.WriteScalingList(span<const u8>{context.weightScale8x8}, index * 64, 64);
            }
        }

        writer.WriteSe(static_cast<i32>(context.h264ParameterSet.flags.secondChromaQpIndexOffset));
        writer.End();

        const auto &encodedHeader{writer.GetByteArray()};
        frame.resize(encodedHeader.size() + context.streamLen);
        std::memcpy(frame.data(), encodedHeader.data(), encodedHeader.size());
        smmu.Read(frame.data() + encodedHeader.size(), static_cast<u32>(state.frameBitstreamOffset), context.streamLen);

        return frame;
    }

    void H264BitWriter::WriteU(i32 value, i32 valueSize) {
        WriteBits(value, valueSize);
    }

    void H264BitWriter::WriteSe(i32 value) {
        WriteExpGolombCodedInt(value);
    }

    void H264BitWriter::WriteUe(u32 value) {
        WriteExpGolombCodedUInt(value);
    }

    void H264BitWriter::End() {
        WriteBit(true);
        Flush();
    }

    void H264BitWriter::WriteBit(bool state) {
        WriteBits(state ? 1 : 0, 1);
    }

    void H264BitWriter::WriteScalingList(span<const u8> list, i32 start, i32 count) {
        std::array<u8, 64> scan{};
        if (count == 16)
            std::memcpy(scan.data(), ZigZagScan.data(), ZigZagScan.size());
        else
            std::memcpy(scan.data(), ZigZagDirect.data(), ZigZagDirect.size());

        u8 lastScale{8};
        for (i32 index{}; index < count; index++) {
            const u8 value{list[static_cast<size_t>(start + scan[static_cast<size_t>(index)])]};
            const i32 deltaScale{static_cast<i32>(value - lastScale)};
            WriteSe(deltaScale);
            lastScale = value;
        }
    }

    void H264BitWriter::WriteBits(i32 value, i32 bitCount) {
        i32 valuePos{};
        i32 remaining{bitCount};

        while (remaining > 0) {
            i32 copySize{remaining};
            const i32 freeBits{GetFreeBufferBits()};
            if (copySize > freeBits)
                copySize = freeBits;

            const i32 mask{(1 << copySize) - 1};
            const i32 srcShift{(bitCount - valuePos) - copySize};
            const i32 dstShift{(bufferSize - bufferPos) - copySize};

            buffer |= ((value >> srcShift) & mask) << dstShift;

            valuePos += copySize;
            bufferPos += copySize;
            remaining -= copySize;
        }
    }

    void H264BitWriter::WriteExpGolombCodedInt(i32 value) {
        const i32 sign{value <= 0 ? 0 : 1};
        if (value < 0)
            value = -value;
        value = (value << 1) - sign;
        WriteExpGolombCodedUInt(static_cast<u32>(value));
    }

    void H264BitWriter::WriteExpGolombCodedUInt(u32 value) {
        const i32 size{32 - std::countl_zero(value + 1)};
        WriteBits(1, size);

        value -= (1U << (size - 1)) - 1;
        WriteBits(static_cast<i32>(value), size - 1);
    }

    i32 H264BitWriter::GetFreeBufferBits() {
        if (bufferPos == bufferSize)
            Flush();
        return bufferSize - bufferPos;
    }

    void H264BitWriter::Flush() {
        if (bufferPos == 0)
            return;
        byteArray.push_back(static_cast<u8>(buffer));
        buffer = 0;
        bufferPos = 0;
    }
}
