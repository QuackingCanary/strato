// SPDX-License-Identifier: MIT
// Copyright © Ryujinx Team and Contributors (https://ryujinx.org/)
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <vector>
#include <soc/smmu.h>
#include "nvdec_registers.h"

namespace skyline::soc::host1x::vdec {
    /**
     * @brief Writes an H.264 bitstream in the format expected by the H.264 specification, used to compose SPS/PPS headers
     */
    class H264BitWriter {
      private:
        i32 bufferSize{8};
        i32 buffer{};
        i32 bufferPos{};
        std::vector<u8> byteArray;

        void WriteBits(i32 value, i32 bitCount);

        void WriteExpGolombCodedInt(i32 value);

        void WriteExpGolombCodedUInt(u32 value);

        i32 GetFreeBufferBits();

        void Flush();

      public:
        /**
         * @brief The following Write methods are based on clause 9.1 in the H.264 specification, WriteSe and WriteUe write in the Exp-Golomb-coded syntax
         */
        void WriteU(i32 value, i32 valueSize);

        void WriteSe(i32 value);

        void WriteUe(u32 value);

        /**
         * @brief Finalises the bitstream with a stop bit and flushes it
         */
        void End();

        void WriteBit(bool state);

        /**
         * @brief Writes a scaling matrix to the stream, based on section 7.3.2.1.1.1 and Table 7-4 in the H.264 specification
         */
        void WriteScalingList(span<const u8> list, i32 start, i32 count);

        std::vector<u8> &GetByteArray() {
            return byteArray;
        }
    };

    /**
     * @brief Composes an Annex-B H.264 bitstream (SPS/PPS + slice data) from the NVDEC picture info for FFmpeg to decode
     */
    class H264 {
      private:
        std::vector<u8> frame; //!< The composed frame data, kept around to avoid reallocation

      public:
        /**
         * @brief Composes the H.264 frame from the given NVDEC register state
         * @return A span of the composed bitstream, valid until the next call to ComposeFrame
         */
        span<const u8> ComposeFrame(const NvdecRegisters &state, SMMU &smmu, bool isFirstFrame);

        struct H264ParameterSet {
            i32 log2MaxPicOrderCntLsbMinus4;                // 0x00
            i32 deltaPicOrderAlwaysZeroFlag;                // 0x04
            i32 frameMbsOnlyFlag;                           // 0x08
            u32 picWidthInMbs;                              // 0x0C
            u32 frameHeightInMapUnits;                      // 0x10
            union {                                         // 0x14
                u32 rawTileFormat;
                struct {
                    u32 tileFormat : 2;
                    u32 gobHeight : 3;
                };
            };
            u32 entropyCodingModeFlag;                      // 0x18
            i32 picOrderPresentFlag;                        // 0x1C
            i32 numRefIdxL0DefaultActive;                   // 0x20
            i32 numRefIdxL1DefaultActive;                   // 0x24
            i32 deblockingFilterControlPresentFlag;         // 0x28
            i32 redundantPicCntPresentFlag;                 // 0x2C
            u32 transform8x8ModeFlag;                       // 0x30
            u32 pitchLuma;                                  // 0x34
            u32 pitchChroma;                                // 0x38
            u32 lumaTopOffset;                              // 0x3C
            u32 lumaBotOffset;                              // 0x40
            u32 lumaFrameOffset;                            // 0x44
            u32 chromaTopOffset;                            // 0x48
            u32 chromaBotOffset;                            // 0x4C
            u32 chromaFrameOffset;                          // 0x50
            u32 histBufferSize;                             // 0x54
            union {                                         // 0x58
                u64 raw;
                struct {
                    u64 mbaffFrame : 1;
                    u64 direct8x8Inference : 1;
                    u64 weightedPred : 1;
                    u64 constrainedIntraPred : 1;
                    u64 refPic : 1;
                    u64 fieldPic : 1;
                    u64 bottomField : 1;
                    u64 secondField : 1;
                    u64 log2MaxFrameNumMinus4 : 4;
                    u64 chromaFormatIdc : 2;
                    u64 picOrderCntType : 2;
                    i64 picInitQpMinus26 : 6;
                    i64 chromaQpIndexOffset : 5;
                    i64 secondChromaQpIndexOffset : 5;
                    u64 weightedBipredIdc : 2;
                    u64 currPicIdx : 7;
                    u64 currColIdx : 5;
                    u64 frameNumber : 16;
                    u64 frameSurfaces : 1;
                    u64 outputMemoryLayout : 1;
                };
            } flags;
        };
        static_assert(sizeof(H264ParameterSet) == 0x60, "H264ParameterSet is an invalid size");

        struct H264DecoderContext {
            u32 _pad0_[18];                     // 0x0000
            u32 streamLen;                      // 0x0048
            u32 _pad1_[3];                      // 0x004C
            H264ParameterSet h264ParameterSet;  // 0x0058
            u32 _pad2_[66];                     // 0x00B8
            std::array<u8, 0x60> weightScale;   // 0x01C0
            std::array<u8, 0x80> weightScale8x8; // 0x0220
        };
        static_assert(sizeof(H264DecoderContext) == 0x2A0, "H264DecoderContext is an invalid size");
    };

    #define ASSERT_POSITION(field, position) static_assert(offsetof(H264::H264DecoderContext, field) == position, "Field " #field " has an invalid position")
    ASSERT_POSITION(streamLen, 0x48);
    ASSERT_POSITION(h264ParameterSet, 0x58);
    ASSERT_POSITION(weightScale, 0x1C0);
    ASSERT_POSITION(weightScale8x8, 0x220);
    #undef ASSERT_POSITION
}
