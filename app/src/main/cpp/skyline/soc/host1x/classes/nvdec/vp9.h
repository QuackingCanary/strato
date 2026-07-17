// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <array>
#include <vector>
#include <soc/smmu.h>
#include "nvdec_registers.h"
#include "vp9_types.h"

namespace skyline::soc::host1x::vdec {
    /**
     * @brief A simple growable byte stream with an explicit position, used by the range encoder for carry propagation
     */
    class ByteStream {
      private:
        std::vector<u8> buffer;
        size_t position{};

      public:
        void WriteByte(u8 byte) {
            if (position == buffer.size()) {
                buffer.push_back(byte);
                position++;
            } else {
                buffer[position++] = byte;
            }
        }

        u8 ReadByte() {
            return buffer[position++];
        }

        void SeekRelative(ssize_t offset) {
            position = static_cast<size_t>(static_cast<ssize_t>(position) + offset);
        }

        void SeekAbsolute(size_t offset) {
            position = offset;
        }

        size_t GetPosition() const {
            return position;
        }

        std::vector<u8> &GetBuffer() {
            return buffer;
        }
    };

    /**
     * @brief Composes VP9 header bitstreams using range encoding, this is the inverse of the boolean decoder in the VP9 specification
     */
    class VpxRangeEncoder {
      private:
        ByteStream baseStream;
        u32 lowValue{};
        u32 range{0xFF};
        i32 count{-24};
        static constexpr i32 HalfProbability{128};

        u8 PeekByte();

      public:
        VpxRangeEncoder();

        VpxRangeEncoder(const VpxRangeEncoder &) = delete;

        VpxRangeEncoder &operator=(const VpxRangeEncoder &) = delete;

        /**
         * @brief Writes the rightmost valueSize bits from value into the stream
         */
        void Write(i32 value, i32 valueSize);

        /**
         * @brief Writes a single bit with half probability
         */
        void Write(bool bit);

        /**
         * @brief Writes a bit encoded with the given probability
         */
        void Write(bool bit, i32 probability);

        /**
         * @brief Signals the end of the bitstream
         */
        void End();

        std::vector<u8> &GetBuffer() {
            return baseStream.GetBuffer();
        }
    };

    /**
     * @brief Composes the uncompressed VP9 frame header bitstream
     */
    class VpxBitStreamWriter {
      private:
        i32 bufferSize{8};
        i32 buffer{};
        i32 bufferPos{};
        std::vector<u8> byteArray;

        void WriteBits(u32 value, u32 bitCount);

        i32 GetFreeBufferBits();

      public:
        void WriteU(u32 value, u32 valueSize);

        void WriteS(i32 value, u32 valueSize);

        /**
         * @brief Writes a delta coded Q value, based on section 6.2.10 of the VP9 specification
         */
        void WriteDeltaQ(u32 value);

        void WriteBit(bool state);

        void Flush();

        std::vector<u8> &GetByteArray() {
            return byteArray;
        }
    };

    /**
     * @brief Composes a complete VP9 bitstream (uncompressed header + compressed header + tile data) from the NVDEC state, reconstructing the probability updates the guest driver stripped out
     */
    class VP9 {
      private:
        std::vector<u8> frame;

        std::array<i8, 4> loopFilterRefDeltas{};
        std::array<i8, 2> loopFilterModeDeltas{};

        Vp9FrameContainer nextFrame{};
        std::array<Vp9EntropyProbs, 4> frameCtxs{};
        bool swapRefIndices{};

        Vp9PictureInfo currentFrameInfo{};
        Vp9EntropyProbs prevFrameProbs{};

        void WriteProbabilityUpdate(VpxRangeEncoder &writer, u8 newProb, u8 oldProb);

        template<typename T, size_t N>
        void WriteProbabilityUpdate(VpxRangeEncoder &writer, const std::array<T, N> &newProb, const std::array<T, N> &oldProb);

        template<typename T, size_t N>
        void WriteProbabilityUpdateAligned4(VpxRangeEncoder &writer, const std::array<T, N> &newProb, const std::array<T, N> &oldProb);

        void WriteProbabilityDelta(VpxRangeEncoder &writer, u8 newProb, u8 oldProb);

        /**
         * @brief The inverse of the 'decode term subexp' routine, section 6.3.4 of the VP9 specification
         */
        void EncodeTermSubExp(VpxRangeEncoder &writer, i32 value);

        bool WriteLessThan(VpxRangeEncoder &writer, i32 value, i32 test);

        void WriteCoefProbabilityUpdate(VpxRangeEncoder &writer, i32 txMode, const std::array<u8, 1728> &newProb, const std::array<u8, 1728> &oldProb);

        /**
         * @brief Writes motion vector probability updates, section 6.3.17 of the VP9 specification
         */
        void WriteMvProbabilityUpdate(VpxRangeEncoder &writer, u8 newProb, u8 oldProb);

        Vp9PictureInfo GetVp9PictureInfo(const NvdecRegisters &state, SMMU &smmu);

        void InsertEntropy(u64 offset, Vp9EntropyProbs &dst, SMMU &smmu);

        Vp9FrameContainer GetCurrentFrame(const NvdecRegisters &state, SMMU &smmu);

        std::vector<u8> ComposeCompressedHeader();

        VpxBitStreamWriter ComposeUncompressedHeader();

      public:
        /**
         * @brief Composes the VP9 frame from the NVDEC state information
         */
        void ComposeFrame(const NvdecRegisters &state, SMMU &smmu);

        /**
         * @return If the most recently composed frame is hidden
         */
        bool WasFrameHidden() const {
            return !currentFrameInfo.showFrame;
        }

        span<const u8> GetFrameBytes() {
            return frame;
        }
    };
}
