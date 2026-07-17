// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <common.h>

namespace skyline::soc::host1x::vdec {
    enum class VideoCodec : u64 {
        None = 0x0,
        H264 = 0x3,
        Vp8 = 0x5,
        H265 = 0x7,
        Vp9 = 0x9,
    };

    /**
     * @brief The NVDEC register file, methods written through the THI directly index into this
     * @note NVDEC uses a 32-bit address space but registers are mapped as 64-bit, all offset registers hold IOVAs shifted right by 8 which are unshifted on write
     */
    struct NvdecRegisters {
        static constexpr size_t RegisterCount{0x178}; //!< The number of 64-bit registers in the file

        union {
            std::array<u64, RegisterCount> regArray;

            struct {
                u64 _pad0_[0x80];                          // 0x0
                VideoCodec setCodecId;                     // 0x80
                u64 _pad1_[0x3F];                          // 0x81
                u64 execute;                               // 0xC0
                u64 _pad2_[0x3F];                          // 0xC1
                union {                                    // 0x100
                    u64 raw;
                    struct {
                        u64 codec : 3;
                        u64 _pad_ : 1;
                        u64 gpTimerOn : 1;
                        u64 _pad0_ : 8;
                        u64 mbTimerOn : 1;
                        u64 intraFramePslc : 1;
                        u64 _pad1_ : 2;
                        u64 allIntraFrame : 1;
                    };
                } controlParams;
                u64 pictureInfoOffset;                     // 0x101
                u64 frameBitstreamOffset;                  // 0x102
                u64 frameNumber;                           // 0x103
                u64 h264SliceDataOffsets;                  // 0x104
                u64 h264MvDumpOffset;                      // 0x105
                u64 _pad3_[3];                             // 0x106
                u64 frameStatsOffset;                      // 0x109
                u64 h264LastSurfaceLumaOffset;             // 0x10A
                u64 h264LastSurfaceChromaOffset;           // 0x10B
                std::array<u64, 17> surfaceLumaOffsets;    // 0x10C
                std::array<u64, 17> surfaceChromaOffsets;  // 0x11D
                u64 _pad4_[0x22];                          // 0x12E
                u64 vp8ProbDataOffset;                     // 0x150
                u64 vp8HeaderPartitionBufOffset;           // 0x151
                u64 _pad5_[0x1E];                          // 0x152
                u64 vp9EntropyProbsOffset;                 // 0x170
                u64 vp9BackwardUpdatesOffset;              // 0x171
                u64 vp9LastFrameSegmapOffset;              // 0x172
                u64 vp9CurrFrameSegmapOffset;              // 0x173
                u64 _pad6_;                                // 0x174
                u64 vp9LastFrameMvsOffset;                 // 0x175
                u64 vp9CurrFrameMvsOffset;                 // 0x176
                u64 _pad7_;                                // 0x177
            };
        };
    };
    static_assert(sizeof(NvdecRegisters) == 0xBC0, "NvdecRegisters is an incorrect size");

    #define ASSERT_REG_POSITION(field, position) static_assert(offsetof(NvdecRegisters, field) == (position * sizeof(u64)), "Field " #field " has an invalid position")

    ASSERT_REG_POSITION(setCodecId, 0x80);
    ASSERT_REG_POSITION(execute, 0xC0);
    ASSERT_REG_POSITION(controlParams, 0x100);
    ASSERT_REG_POSITION(pictureInfoOffset, 0x101);
    ASSERT_REG_POSITION(frameBitstreamOffset, 0x102);
    ASSERT_REG_POSITION(frameNumber, 0x103);
    ASSERT_REG_POSITION(h264SliceDataOffsets, 0x104);
    ASSERT_REG_POSITION(frameStatsOffset, 0x109);
    ASSERT_REG_POSITION(h264LastSurfaceLumaOffset, 0x10A);
    ASSERT_REG_POSITION(h264LastSurfaceChromaOffset, 0x10B);
    ASSERT_REG_POSITION(surfaceLumaOffsets, 0x10C);
    ASSERT_REG_POSITION(surfaceChromaOffsets, 0x11D);
    ASSERT_REG_POSITION(vp8ProbDataOffset, 0x150);
    ASSERT_REG_POSITION(vp8HeaderPartitionBufOffset, 0x151);
    ASSERT_REG_POSITION(vp9EntropyProbsOffset, 0x170);
    ASSERT_REG_POSITION(vp9BackwardUpdatesOffset, 0x171);
    ASSERT_REG_POSITION(vp9LastFrameSegmapOffset, 0x172);
    ASSERT_REG_POSITION(vp9CurrFrameSegmapOffset, 0x173);
    ASSERT_REG_POSITION(vp9LastFrameMvsOffset, 0x175);
    ASSERT_REG_POSITION(vp9CurrFrameMvsOffset, 0x176);

    #undef ASSERT_REG_POSITION
}
